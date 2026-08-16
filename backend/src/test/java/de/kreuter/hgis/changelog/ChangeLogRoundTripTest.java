package de.kreuter.hgis.changelog;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.FieldType;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.EditService;
import de.kreuter.hgis.features.FeatureQueryService;
import de.kreuter.hgis.features.dto.EditDtos;
import de.kreuter.hgis.features.dto.FeatureDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Actually replays a captured {@code feature.delete} row back through {@link
 * EditService}, the way a future "undo delete" would have to -- not just inspects the
 * JSON. CONTRACT.md "Schreibstufe" calls this the sole fallback for a deleted object;
 * a fallback nobody has driven end to end is only a claim, and a first pass at
 * exercising it found two types that silently lost data on the way back: {@code
 * numeric} (precision, via a {@code double} detour on read) and {@code bytea} (unusable
 * outright, and since a {@code Create} is all-or-nothing, that failure took every other
 * value of the same object down with it).
 *
 * <p>{@link #everyFieldTypeSurvivesADeleteAndReplay} iterates {@link FieldType#values()}
 * rather than naming each column by hand, so a type added to that enum later without a
 * column here shows up as a build failure on the very next run -- the guarantee this
 * class exists to keep is only as good as the last type anyone remembered to add.
 * {@code uuid} and {@code bytea} are added explicitly alongside it: real column types
 * (via import, {@code TypeMapper}) that {@link FieldType} deliberately excludes from the
 * field-creation UI, not absent from the data this fallback has to carry.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChangeLogRoundTripTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private EditService editService;

	@Autowired
	private FeatureQueryService queryService;

	@Autowired
	private ChangeLogRepository changeLogRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private LayerFieldRepository fieldRepository;

	@Autowired
	private JdbcClient jdbc;

	private Project project;

	@BeforeEach
	void createProject() {
		project = projectRepository.saveAndFlush(
				new Project("Rundlauftest " + UUID.randomUUID(), null, 25832, "osm"));
	}

	@AfterEach
	void dropProject() {
		layerRepository.findByProjectOrdered(project.getId())
				.forEach(layer -> jdbc.sql("DROP TABLE IF EXISTS "
						+ SqlIdentifier.quoteLayerTable(layer.getTableName())).update());
		projectRepository.deleteById(project.getId());
	}

	// --- every attribute type ----------------------------------------------------------

	@Test
	@DisplayName("every field type -- including a null one, numeric at full precision and bytea -- "
			+ "survives a delete-and-replay through the real EditService path")
	void everyFieldTypeSurvivesADeleteAndReplay() {
		UUID layerId = UUID.randomUUID();
		String tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);

		List<FieldType> types = List.of(FieldType.values());
		StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(table)
				.append(" (fid bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
						+ "geom geometry(MultiPolygon, 25832) NOT NULL");
		for (FieldType type : types) {
			ddl.append(", ").append(columnOf(type)).append(' ').append(type.pgType());
		}
		ddl.append(", f_uuid uuid, f_bytea bytea)");
		jdbc.sql(ddl.toString()).update();

		Layer layer = layerRepository.saveAndFlush(
				new Layer(layerId, project, "Alle Typen", tableName, "MULTIPOLYGON", 25832));
		int ordinal = 0;
		for (FieldType type : types) {
			fieldRepository.saveAndFlush(
					new LayerField(layer, columnOf(type), columnOf(type), type.pgType(), ordinal++));
		}
		fieldRepository.saveAndFlush(new LayerField(layer, "f_uuid", "f_uuid", "uuid", ordinal++));
		fieldRepository.saveAndFlush(new LayerField(layer, "f_bytea", "f_bytea", "bytea", ordinal));

		// One representative, type-stressing value per column -- the numeric one is the
		// reported bug verbatim (20 significant digits, well past double's ~15-17), and
		// f_time is left out on purpose so at least one column genuinely carries null
		// through the whole round trip, not just a type that happens to tolerate it.
		String text = "Straße \"Am Wäldchen\" – Café";
		int integer = -123_456;
		long bigint = Long.MAX_VALUE;
		double doubleValue = 3.141592653589793;
		BigDecimal numeric = new BigDecimal("12345678901234567890.123456789");
		boolean bool = true;
		LocalDate date = LocalDate.of(2024, 3, 1);
		Instant timestamp = Instant.parse("2024-06-15T10:30:00.123Z");
		UUID uuidValue = UUID.randomUUID();
		byte[] bytea = { 0, 1, 2, 63, 127, -128, -1, 5 };

		long fid = jdbc.sql("INSERT INTO " + table + " (geom, "
						+ columnOf(FieldType.TEXT) + ", " + columnOf(FieldType.INTEGER) + ", "
						+ columnOf(FieldType.BIGINT) + ", " + columnOf(FieldType.DOUBLE) + ", "
						+ columnOf(FieldType.NUMERIC) + ", " + columnOf(FieldType.BOOLEAN) + ", "
						+ columnOf(FieldType.DATE) + ", " + columnOf(FieldType.TIMESTAMP) + ", "
						+ "f_uuid, f_bytea) VALUES ("
						+ "ST_Multi(ST_GeomFromText('POLYGON((0 0,1 0,1 1,0 1,0 0))', 25832)), "
						+ ":text, :integer, :bigint, :double, :numeric, :bool, :date, :timestamp, :uuid, :bytea)"
						+ " RETURNING fid")
				.param("text", text).param("integer", integer).param("bigint", bigint)
				.param("double", doubleValue).param("numeric", numeric).param("bool", bool)
				.param("date", date).param("timestamp", timestamp.atOffset(ZoneOffset.UTC))
				.param("uuid", uuidValue).param("bytea", bytea)
				.query(Long.class)
				.single();

		editService.apply(layer.getId(), new EditDtos.Request(null, null, List.of(fid), false), null);

		JsonNode capturedRow = capturedRowFor(layer.getId());
		long newFid = replay(layer.getId(), capturedRow);

		FeatureDtos.Feature feature = queryService.get(layer.getId(), newFid);
		Map<String, Object> props = feature.properties();

		assertThat(props.get(columnOf(FieldType.TEXT))).isEqualTo(text);
		assertThat(props.get(columnOf(FieldType.INTEGER))).isEqualTo(integer);
		assertThat(((Number) props.get(columnOf(FieldType.BIGINT))).longValue()).isEqualTo(bigint);
		assertThat((Double) props.get(columnOf(FieldType.DOUBLE))).isEqualTo(doubleValue);
		assertThat((BigDecimal) props.get(columnOf(FieldType.NUMERIC)))
				.as("the reported bug: full precision must survive, not just the value rounded to a double")
				.isEqualByComparingTo(numeric);
		assertThat(((BigDecimal) props.get(columnOf(FieldType.NUMERIC))).unscaledValue().toString())
				.as("isEqualByComparingTo alone would also accept a value that lost trailing digits "
						+ "and gained trailing zeros instead -- the digit string itself has to match")
				.isEqualTo(numeric.unscaledValue().toString());
		assertThat(props.get(columnOf(FieldType.BOOLEAN))).isEqualTo(bool);
		assertThat(props.get(columnOf(FieldType.DATE))).isEqualTo(date);
		assertThat(((java.util.Date) props.get(columnOf(FieldType.TIMESTAMP))).toInstant()).isEqualTo(timestamp);
		assertThat(props.get(columnOf(FieldType.TIME))).as("deliberately left null").isNull();
		assertThat(props.get("f_uuid")).isEqualTo(uuidValue);
		assertThat((byte[]) props.get("f_bytea"))
				.as("the reported bug: bytea must decode, not throw and take the rest of the row down with it")
				.isEqualTo(bytea);
	}

	// --- every geometry shape ------------------------------------------------------------

	@Test
	@DisplayName("a point, a line and a multipolygon with a hole all survive a delete-and-replay unchanged")
	void everyGeometryShapeSurvivesADeleteAndReplay() {
		// SRID 4326 on purpose: the captured geometry is already EPSG:4326 (see
		// FeatureDeleteCapture), so storing the layer in that same CRS makes the
		// insert-side ST_Transform a no-op and this test about topology, not about
		// reprojection's own floating-point rounding.
		UUID layerId = UUID.randomUUID();
		String tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(Geometry, 4326) NOT NULL
				)
				""".formatted(table)).update();
		Layer layer = layerRepository.saveAndFlush(
				new Layer(layerId, project, "Formen", tableName, "GEOMETRY", 4326));

		String point = "{\"type\":\"Point\",\"coordinates\":[9.98,53.54]}";
		String line = "{\"type\":\"LineString\",\"coordinates\":"
				+ "[[9.98,53.54],[9.985,53.545],[9.99,53.55]]}";
		// Two parts, the first with a hole -- CONTRACT.md's own example of what nothing
		// had actually driven through the fallback before this test.
		String multiPolygonWithHole = "{\"type\":\"MultiPolygon\",\"coordinates\":["
				+ "[[[9.90,53.50],[9.95,53.50],[9.95,53.55],[9.90,53.55],[9.90,53.50]],"
				+ "[[9.91,53.51],[9.92,53.51],[9.92,53.52],[9.91,53.52],[9.91,53.51]]],"
				+ "[[[10.10,53.60],[10.15,53.60],[10.15,53.65],[10.10,53.65],[10.10,53.60]]]]}";

		for (String geoJson : List.of(point, line, multiPolygonWithHole)) {
			long fid = jdbc.sql("INSERT INTO " + table + " (geom) VALUES "
							+ "(ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326)) RETURNING fid")
					.param("g", geoJson)
					.query(Long.class)
					.single();

			editService.apply(layer.getId(), new EditDtos.Request(null, null, List.of(fid), false), null);
			JsonNode capturedRow = capturedRowFor(layer.getId());
			long newFid = replay(layer.getId(), capturedRow);

			Boolean equal = jdbc.sql("SELECT ST_Equals(geom, ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326)) "
							+ "FROM " + table + " WHERE fid = :fid")
					.param("g", geoJson).param("fid", newFid)
					.query(Boolean.class)
					.single();
			assertThat(equal).as("shape %s round-trips unchanged", geoJson).isTrue();
		}
	}

	// --- shared -----------------------------------------------------------------------

	private static String columnOf(FieldType type) {
		return "f_" + type.name().toLowerCase(Locale.ROOT);
	}

	/** The most recent {@code feature.delete} entry logged for this layer, as parsed JSON. */
	private JsonNode capturedRowFor(UUID layerId) {
		ChangeLogEntry entry = changeLogRepository
				.findByProjectIdOrderByOccurredAtDescIdDesc(project.getId(),
						org.springframework.data.domain.PageRequest.of(0, 1000))
				.stream()
				.filter(e -> layerId.equals(e.getLayerId()) && e.getAction().equals(ChangeLogAction.FEATURE_DELETE))
				.findFirst()
				.orElseThrow();
		JsonNode rows = MAPPER.readTree(entry.getDeletedRows());
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	/**
	 * Rebuilds the captured row as the JSON body of a real {@code POST .../edits} request
	 * and deserialises it through {@link EditDtos.Request} itself -- the same class, the
	 * same generic {@code Map<String, Object>} property type an actual HTTP request goes
	 * through -- rather than converting the captured {@link JsonNode} by hand, which would
	 * only prove that Java objects compare equal to themselves.
	 */
	private long replay(UUID layerId, JsonNode capturedRow) {
		String body = "{\"creates\":[{\"clientId\":-1,\"geometry\":"
				+ capturedRow.get("geometry") + ",\"properties\":" + capturedRow.get("properties") + "}]}";
		EditDtos.Request request = MAPPER.readValue(body, EditDtos.Request.class);
		EditDtos.Response response = editService.apply(layerId, request, null);
		return response.createdFids().get(-1L);
	}
}
