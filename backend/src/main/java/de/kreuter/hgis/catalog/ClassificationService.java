package de.kreuter.hgis.catalog;

import de.kreuter.hgis.catalog.dto.ClassificationDtos;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the two questions the symbology UI has to ask the data before it can offer a
 * classification: where the class boundaries lie, and which values actually occur.
 *
 * <p>Both are aggregates over a whole layer table, so both are computed in PostgreSQL.
 * Streaming the column into Java to sort it there would move a million values across the
 * wire for a handful of numbers.
 *
 * <p>The field name comes from the client and is resolved through {@code layer_field}
 * exactly like everywhere else -- the client names a field, this class decides which
 * column that is, and {@link SqlIdentifier} quotes it.
 */
@Service
public class ClassificationService {

	/** Below two there is nothing to classify; above twelve no legend is readable any more. */
	private static final int MIN_CLASSES = 2;
	private static final int MAX_CLASSES = 12;

	private static final int DEFAULT_VALUE_LIMIT = 100;
	private static final int MAX_VALUE_LIMIT = 1000;

	private static final String QUANTILE = ClassificationMethods.QUANTILE;
	private static final String EQUAL_INTERVAL = ClassificationMethods.EQUAL_INTERVAL;
	private static final String NATURAL_BREAKS = ClassificationMethods.NATURAL_BREAKS;

	private final LayerRepository layerRepository;
	private final LayerFieldRepository fieldRepository;
	private final JdbcClient jdbc;

	ClassificationService(LayerRepository layerRepository, LayerFieldRepository fieldRepository,
			JdbcClient jdbc) {
		this.layerRepository = layerRepository;
		this.fieldRepository = fieldRepository;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public ClassificationDtos.Breaks classify(UUID layerId, String field, String method, int classes) {
		if (classes < MIN_CLASSES || classes > MAX_CLASSES) {
			throw new BadRequestException("classes muss zwischen " + MIN_CLASSES + " und "
					+ MAX_CLASSES + " liegen. Wert war " + classes + ".");
		}
		String normalizedMethod = normalizeMethod(method);

		Layer layer = require(layerId);
		LayerField target = requireNumericField(layerId, field);
		String table = SqlIdentifier.quoteLayerTable(layer.getTableName());
		String column = SqlIdentifier.quoteColumn(target.getColumnName());

		Extremes extremes = extremes(table, column);
		List<Double> breaks = extremes.valueCount() == 0 ? List.of() : switch (normalizedMethod) {
			case QUANTILE -> quantileBreaks(table, column, classes);
			case EQUAL_INTERVAL -> equalIntervalBreaks(extremes, classes);
			case NATURAL_BREAKS -> naturalBreaks(table, column, classes, extremes);
			default -> throw new IllegalStateException(normalizedMethod);
		};

		return new ClassificationDtos.Breaks(target.getColumnName(), normalizedMethod,
				strictlyAscending(breaks), extremes.min(), extremes.max(), extremes.nullCount());
	}

	@Transactional(readOnly = true)
	public ClassificationDtos.Values values(UUID layerId, String field, Integer limit) {
		Layer layer = require(layerId);
		LayerField target = LayerFields.require(field, fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId));

		int size = Math.clamp(limit == null ? DEFAULT_VALUE_LIMIT : limit, 1, MAX_VALUE_LIMIT);

		// One row more than asked for: if it shows up, there are more distinct values than
		// fit -- the same trick the feature paging uses, and it avoids a second COUNT
		// DISTINCT over the whole table just to set a boolean.
		List<Map<String, Object>> rows = jdbc.sql("""
						SELECT %1$s AS value, count(*) AS occurrences
						FROM %2$s
						GROUP BY 1
						ORDER BY 2 DESC, 1 ASC NULLS LAST
						LIMIT :limit
						"""
						.formatted(SqlIdentifier.quoteColumn(target.getColumnName()),
								SqlIdentifier.quoteLayerTable(layer.getTableName())))
				.param("limit", size + 1)
				.query()
				.listOfRows();

		boolean truncated = rows.size() > size;
		List<ClassificationDtos.ValueCount> values = (truncated ? rows.subList(0, size) : rows).stream()
				.map(row -> new ClassificationDtos.ValueCount(
						toJsonValue(row.get("value")),
						((Number) row.get("occurrences")).longValue()))
				.toList();

		return new ClassificationDtos.Values(target.getColumnName(), values, truncated);
	}

	// --- boundaries -------------------------------------------------------------------

	private record Extremes(Double min, Double max, long valueCount, long nullCount) {
	}

	private Extremes extremes(String table, String column) {
		return jdbc.sql("""
						SELECT min(%1$s)::double precision            AS min_value,
						       max(%1$s)::double precision            AS max_value,
						       count(%1$s)                            AS value_count,
						       count(*) FILTER (WHERE %1$s IS NULL)   AS null_count
						FROM %2$s
						""".formatted(column, table))
				.query((rs, rowNum) -> new Extremes(
						nullableDouble(rs, "min_value"),
						nullableDouble(rs, "max_value"),
						rs.getLong("value_count"),
						rs.getLong("null_count")))
				.single();
	}

	/** Equal counts per class: each boundary is the value that as many features fall below as above it. */
	private List<Double> quantileBreaks(String table, String column, int classes) {
		return jdbc.sql("""
						SELECT percentile_cont(
						           ARRAY(SELECT i::double precision / :classes
						                 FROM generate_series(0, :classes) AS i))
						       WITHIN GROUP (ORDER BY %1$s::double precision) AS breaks
						FROM %2$s
						WHERE %1$s IS NOT NULL
						""".formatted(column, table))
				.param("classes", classes)
				.query((rs, rowNum) -> doubleArray(rs.getArray("breaks")))
				.single();
	}

	/** Equal width per class. Says nothing about how the features are distributed inside them. */
	private static List<Double> equalIntervalBreaks(Extremes extremes, int classes) {
		double min = extremes.min();
		double width = (extremes.max() - min) / classes;

		List<Double> breaks = new ArrayList<>(classes + 1);
		for (int i = 0; i < classes; i++) {
			breaks.add(min + i * width);
		}
		// The last boundary is the maximum itself rather than min + classes * width, so
		// repeated floating point addition cannot leave the largest feature outside its
		// own class.
		breaks.add(extremes.max());
		return breaks;
	}

	/**
	 * Natural breaks, <strong>approximated with {@code ntile}</strong>.
	 *
	 * <p>This is not Jenks. Jenks minimises the variance within each class, which costs a
	 * pass over every pair of values -- quadratic in the number of features, and a layer
	 * with a hundred thousand rows makes that unusable. What happens here is the cheap
	 * substitute the plan calls for: {@code ntile} cuts the sorted values into buckets of
	 * equal size and the lowest value of each bucket becomes a boundary.
	 *
	 * <p>Worth knowing before trusting the name: equal-sized buckets are what
	 * {@link #quantileBreaks} computes as well, so on most data the two methods answer
	 * nearly the same thing. The method is offered separately because it is the label
	 * users of desktop GIS look for, and because a real Jenks can replace this
	 * implementation later without the API changing.
	 */
	private List<Double> naturalBreaks(String table, String column, int classes, Extremes extremes) {
		List<Double> lowerBounds = jdbc.sql("""
						SELECT min(v)::double precision AS lower_bound
						FROM (
						  SELECT %1$s::double precision AS v,
						         ntile(:classes) OVER (ORDER BY %1$s) AS bucket
						  FROM %2$s
						  WHERE %1$s IS NOT NULL
						) AS buckets
						GROUP BY bucket
						ORDER BY bucket
						""".formatted(column, table))
				.param("classes", classes)
				.query(Double.class)
				.list();

		List<Double> breaks = new ArrayList<>(lowerBounds);
		breaks.add(extremes.max());
		return breaks;
	}

	/**
	 * Drops boundaries that do not increase.
	 *
	 * <p>A column with fewer distinct values than requested classes produces repeats, and a
	 * repeated boundary is an empty class -- MapLibre's {@code step} rejects stops that do
	 * not ascend, so passing them on would produce a style that throws in the browser
	 * rather than a legend with a gap.
	 */
	private static List<Double> strictlyAscending(List<Double> breaks) {
		List<Double> result = new ArrayList<>(breaks.size());
		for (Double value : breaks) {
			if (value != null && (result.isEmpty() || value > result.get(result.size() - 1))) {
				result.add(value);
			}
		}
		return result;
	}

	// --- helpers ----------------------------------------------------------------------

	private String normalizeMethod(String method) {
		if (method == null || method.isBlank()) {
			return QUANTILE;
		}
		return ClassificationMethods.require(method);
	}

	private LayerField requireNumericField(UUID layerId, String field) {
		LayerField resolved = LayerFields.require(field,
				fieldRepository.findByLayerIdOrderByOrdinalAsc(layerId));
		if (!LayerFields.isNumeric(resolved)) {
			throw new BadRequestException("Feld " + resolved.getSourceName() + " ist vom Typ "
					+ resolved.getDataType() + ". Klasseneinteilung ist für diesen Feldtyp nicht möglich.");
		}
		return resolved;
	}

	/** Both aggregates read the payload table, so a map image (kind WMS) is rejected up front. */
	private Layer require(UUID layerId) {
		Layer layer = layerRepository.findById(layerId)
				.orElseThrow(() -> new NotFoundException("Layer " + layerId + " existiert nicht"));
		layer.requireVector();
		return layer;
	}

	private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
		double value = rs.getDouble(column);
		return rs.wasNull() ? null : value;
	}

	private static List<Double> doubleArray(Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		return List.of((Double[]) array.getArray());
	}

	/**
	 * Keeps the JSON to the three types a category value can be matched on. Dates, uuids
	 * and the like become their text form -- which is also what they look like as a tile
	 * property, so a value picked here matches what MapLibre later compares against.
	 */
	private static Object toJsonValue(Object value) {
		return switch (value) {
			case null -> null;
			case String text -> text;
			case Number number -> number;
			case Boolean flag -> flag;
			default -> value.toString();
		};
	}
}
