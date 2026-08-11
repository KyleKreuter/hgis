package de.kreuter.hgis.ingest;

import de.kreuter.hgis.common.GeometryType;
import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceReader;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

/**
 * In-memory {@link SourceReader} for the writer track's own tests.
 *
 * Track A's real readers (Shapefile, GeoPackage, GeoJSON, CSV) do not exist yet, so this
 * stands in for one: it produces synthetic features straight from Java, exercising
 * exactly the {@code SourceReader} contract that {@link FeatureWriter} and
 * {@link ImportService} are written against, without depending on any file format.
 */
final class FakeSourceReader implements SourceReader {

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

	private final SourceSchema schema;
	private final List<SourceFeature> features;
	private final long skippedCount;
	private boolean closed;

	private FakeSourceReader(SourceSchema schema, List<SourceFeature> features, long skippedCount) {
		this.schema = schema;
		this.features = features;
		this.skippedCount = skippedCount;
	}

	@Override
	public SourceSchema schema() {
		return schema;
	}

	@Override
	public Stream<SourceFeature> features() {
		return features.stream();
	}

	@Override
	public long skippedCount() {
		return skippedCount;
	}

	@Override
	public void close() {
		closed = true;
	}

	boolean isClosed() {
		return closed;
	}

	// --- scenario builders ---------------------------------------------------------------

	/** {@code count} features with real MultiPolygon geometry and two attributes, the shape
	 *  of a realistic bulk import. */
	static FakeSourceReader bulkPolygons(int count, int srid) {
		List<SourceField> fields = List.of(
				new SourceField("name", String.class),
				new SourceField("area", Double.class));

		List<SourceFeature> features = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			MultiPolygon geometry = multiSquare(500_000 + i, 5_800_000, 10);
			Map<String, Object> attributes = new LinkedHashMap<>();
			attributes.put("name", "Feature " + i);
			attributes.put("area", 100.0 + i);
			features.add(new SourceFeature(geometry, attributes));
		}

		SourceSchema schema = new SourceSchema(GeometryType.MULTIPOLYGON, srid, fields,
				"UTF-8", SourceSchema.CrsConfidence.DECLARED, (long) count);
		return new FakeSourceReader(schema, features, 0);
	}

	/** Same schema and features as {@link #bulkPolygons}, but reporting extra skipped
	 *  records so the 5% skip-ratio rule can be exercised without a real broken feature. */
	static FakeSourceReader withSkippedFeatures(int processedCount, long skippedCount, int srid) {
		FakeSourceReader base = bulkPolygons(processedCount, srid);
		return new FakeSourceReader(base.schema, base.features, skippedCount);
	}

	/** One feature with a plain, non-multi Polygon -- the reader never promotes geometry
	 *  itself, that is the writer's job via {@code ST_Multi}. */
	static FakeSourceReader singlePolygon(int srid) {
		List<SourceField> fields = List.of(new SourceField("name", String.class));
		Polygon polygon = square(500_000, 5_800_000, 25);
		SourceFeature feature = new SourceFeature(polygon, Map.of("name", "Einzelpolygon"));

		SourceSchema schema = new SourceSchema(GeometryType.MULTIPOLYGON, srid, fields,
				"UTF-8", SourceSchema.CrsConfidence.DECLARED, 1L);
		return new FakeSourceReader(schema, List.of(feature), 0);
	}

	/** Attribute names that must be normalised: umlauts, and one literally called "geom",
	 *  which collides with the table's built-in geometry column. */
	static FakeSourceReader umlautAndReservedNames(int srid) {
		List<SourceField> fields = List.of(
				new SourceField("Gebäudehöhe", Double.class),
				new SourceField("geom", String.class),
				new SourceField("Straße", String.class));

		List<SourceFeature> features = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Map<String, Object> attributes = new LinkedHashMap<>();
			attributes.put("Gebäudehöhe", 12.5 + i);
			attributes.put("geom", "Attribut, nicht die Geometriespalte");
			attributes.put("Straße", "Musterstraße " + i);
			features.add(new SourceFeature(multiSquare(500_000 + i, 5_800_000, 10), attributes));
		}

		SourceSchema schema = new SourceSchema(GeometryType.MULTIPOLYGON, srid, fields,
				"UTF-8", SourceSchema.CrsConfidence.DECLARED, 3L);
		return new FakeSourceReader(schema, features, 0);
	}

	/**
	 * {@code goodCount} valid features followed by one whose "count" attribute holds text
	 * where the schema declares an Integer -- a genuine PostgreSQL type error on insert,
	 * simulating a failure partway through an import.
	 */
	static FakeSourceReader failingMidway(int goodCount, int srid) {
		List<SourceField> fields = List.of(
				new SourceField("name", String.class),
				new SourceField("count", Integer.class));

		List<SourceFeature> features = new ArrayList<>(goodCount + 1);
		for (int i = 0; i < goodCount; i++) {
			features.add(new SourceFeature(multiSquare(500_000 + i, 5_800_000, 10),
					Map.of("name", "Feature " + i, "count", i)));
		}

		Map<String, Object> badAttributes = new LinkedHashMap<>();
		badAttributes.put("name", "Kaputtes Feature");
		badAttributes.put("count", "nicht-numerisch"); // violates the declared Integer type
		features.add(new SourceFeature(multiSquare(500_000 + goodCount, 5_800_000, 10), badAttributes));

		SourceSchema schema = new SourceSchema(GeometryType.MULTIPOLYGON, srid, fields,
				"UTF-8", SourceSchema.CrsConfidence.DECLARED, (long) (goodCount + 1));
		return new FakeSourceReader(schema, features, 0);
	}

	private static MultiPolygon multiSquare(double x, double y, double size) {
		return GEOMETRY_FACTORY.createMultiPolygon(new Polygon[] { square(x, y, size) });
	}

	private static Polygon square(double x, double y, double size) {
		Coordinate[] coordinates = {
				new Coordinate(x, y),
				new Coordinate(x + size, y),
				new Coordinate(x + size, y + size),
				new Coordinate(x, y + size),
				new Coordinate(x, y),
		};
		return GEOMETRY_FACTORY.createPolygon(coordinates);
	}
}
