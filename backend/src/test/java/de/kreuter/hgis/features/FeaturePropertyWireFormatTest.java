package de.kreuter.hgis.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins down the wire format of {@code properties} on the feature API -- both directions --
 * against a real PostGIS table, one column per PostgreSQL type a layer can have.
 *
 * <p>{@code FeatureQueryService.toFeature} converts a {@code date} column's raw
 * {@link java.sql.Date} into a {@link java.time.LocalDate} before handing the row to
 * Jackson; every other type already reads back correctly as whatever the JDBC driver
 * hands over -- {@code time} included: {@link java.sql.Time} also extends
 * {@code java.util.Date}, but Jackson's handling of it already formats the time of day
 * directly rather than going through the timezone-dependent instant logic that made
 * {@code date} read back wrong. {@code EditService.collectProperties} looks up each
 * property's {@code layer_field.data_type} and parses the incoming JSON value into the
 * matching Java type before binding it -- {@code date}, {@code time}, {@code timestamptz},
 * {@code uuid} and {@code bytea} need that parsing because JDBC would otherwise bind
 * Jackson's generic {@code String} as varchar, which PostgreSQL has no implicit cast for.
 * A value that does not fit its column -- an unparsable date, a string where a number
 * belongs -- is rejected as a 400 naming the field, rather than reaching PostgreSQL and
 * surfacing as a bare 500.
 *
 * <p>This test is the contract for both directions: what {@code GET} produces for each
 * of the eleven types, and that writing exactly that value back through the edit endpoint
 * round-trips to the same value again.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeaturePropertyWireFormatTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final String STORED_TEXT = "Straße äöü";
	private static final int STORED_INT = 42;
	// Past Integer.MAX_VALUE on purpose: proves bigint isn't silently narrowed to a 32-bit int.
	private static final long STORED_BIGINT = 10_000_000_000L;
	private static final double STORED_DOUBLE = 12.75;
	private static final BigDecimal STORED_NUMERIC = new BigDecimal("1234.50");
	private static final LocalDate STORED_DATE = LocalDate.of(2024, 3, 1);
	private static final OffsetDateTime STORED_TIMESTAMP = OffsetDateTime.parse("2024-03-01T10:15:30+02:00");
	private static final LocalTime STORED_TIME = LocalTime.of(8, 15, 30);
	private static final UUID STORED_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final byte[] STORED_BYTES = { (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF };

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private LayerFieldRepository fieldRepository;

	private Project project;
	private Layer layer;
	private String tableName;
	private long filledFid;
	private long nullFid;

	@BeforeAll
	void createLayer() {
		project = projectRepository.saveAndFlush(
				new Project("Wire-Format-Test " + UUID.randomUUID(), null, 4326, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom      geometry(MultiPoint, 4326) NOT NULL,
				    txt       text,
				    intcol    integer,
				    bigcol    bigint,
				    dblcol    double precision,
				    numcol    numeric(12,2),
				    boolcol   boolean,
				    datecol   date,
				    tscol     timestamptz,
				    uuidcol   uuid,
				    byteacol  bytea,
				    timecol   time
				)
				""".formatted(table())).update();

		filledFid = insertFilledRow();
		nullFid = insertRow(null, null, null, null, null, null, null, null, null, null, null);

		Layer newLayer = new Layer(layerId, project, "Wire-Format", tableName, "MULTIPOINT", 4326);
		newLayer.setFeatureCount(2);
		layer = layerRepository.saveAndFlush(newLayer);

		fieldRepository.saveAndFlush(new LayerField(layer, "Text", "txt", "text", 0));
		fieldRepository.saveAndFlush(new LayerField(layer, "Ganzzahl", "intcol", "integer", 1));
		fieldRepository.saveAndFlush(new LayerField(layer, "Große Zahl", "bigcol", "bigint", 2));
		fieldRepository.saveAndFlush(new LayerField(layer, "Kommazahl", "dblcol", "double precision", 3));
		fieldRepository.saveAndFlush(new LayerField(layer, "Betrag", "numcol", "numeric", 4));
		fieldRepository.saveAndFlush(new LayerField(layer, "Wahrheitswert", "boolcol", "boolean", 5));
		fieldRepository.saveAndFlush(new LayerField(layer, "Datum", "datecol", "date", 6));
		fieldRepository.saveAndFlush(new LayerField(layer, "Zeitstempel", "tscol", "timestamptz", 7));
		fieldRepository.saveAndFlush(new LayerField(layer, "Kennung", "uuidcol", "uuid", 8));
		fieldRepository.saveAndFlush(new LayerField(layer, "Binärdaten", "byteacol", "bytea", 9));
		fieldRepository.saveAndFlush(new LayerField(layer, "Uhrzeit", "timecol", "time", 10));
	}

	@AfterAll
	void dropLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + table()).update();
		layerRepository.deleteById(layer.getId());
		projectRepository.deleteById(project.getId());
	}

	// --- reading ---------------------------------------------------------------------

	@Test
	@DisplayName("text, integer, bigint, double precision, numeric and boolean keep their native JSON type")
	void readsNativeJsonTypesDirectly() throws Exception {
		FeatureResponse feature = getFeature(filledFid);
		JsonNode properties = feature.json().get("properties");

		assertThat(properties.get("txt").isString()).isTrue();
		assertThat(properties.get("txt").asString())
				.as("umlauts survive as UTF-8")
				.isEqualTo(STORED_TEXT);

		assertThat(properties.get("intcol").isIntegralNumber()).isTrue();
		assertThat(properties.get("intcol").asInt()).isEqualTo(STORED_INT);

		assertThat(properties.get("bigcol").isIntegralNumber()).isTrue();
		assertThat(properties.get("bigcol").asLong())
				.as("bigint survives past Integer.MAX_VALUE without becoming a string")
				.isEqualTo(STORED_BIGINT);

		assertThat(properties.get("dblcol").isFloatingPointNumber()).isTrue();
		assertThat(properties.get("dblcol").asDouble()).isEqualTo(STORED_DOUBLE);

		assertThat(properties.get("numcol").isNumber()).isTrue();
		assertThat(properties.get("numcol").asDecimal()).isEqualByComparingTo(STORED_NUMERIC);
		// The parsed tree normalises "1234.50" to 1234.5 (see the round-trip test below),
		// so the scale is only provable against the raw body Jackson actually wrote.
		assertThat(feature.raw())
				.as("numeric(12,2) keeps its scale on the wire, trailing zero included")
				.contains("\"numcol\":1234.50");

		assertThat(properties.get("boolcol").isBoolean()).isTrue();
		assertThat(properties.get("boolcol").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("date reads back as a plain ISO date, independent of the server's time zone")
	void readsDateAsAPlainIsoDate() throws Exception {
		JsonNode node = getFeature(filledFid).json().get("properties").get("datecol");

		// FeatureQueryService.toFeature() converts the java.sql.Date the JDBC driver hands
		// back into a java.time.LocalDate before it reaches Jackson, so the wire value is
		// only ever the calendar date -- never a timestamp, and never dependent on the
		// JVM's default time zone the way a raw java.util.Date would be. Same shape
		// GeoJsonExportService already gets for free from PostgreSQL's own to_jsonb().
		assertThat(node.isString()).isTrue();
		assertThat(node.asString()).isEqualTo(STORED_DATE.toString());
	}

	@Test
	@DisplayName("timestamptz reads back as an ISO instant in UTC")
	void readsTimestamptzAsIsoUtcString() throws Exception {
		JsonNode node = getFeature(filledFid).json().get("properties").get("tscol");

		assertThat(node.isString()).isTrue();
		// Unlike date, timestamptz already carries an absolute instant, so this value does
		// not depend on the server's default time zone the way readsDateAsA... does.
		assertThat(node.asString()).isEqualTo("2024-03-01T08:15:30.000Z");
		assertThat(Instant.parse(node.asString())).isEqualTo(STORED_TIMESTAMP.toInstant());
	}

	@Test
	@DisplayName("uuid reads back as its plain canonical string")
	void readsUuidAsPlainString() throws Exception {
		JsonNode node = getFeature(filledFid).json().get("properties").get("uuidcol");

		assertThat(node.isString()).isTrue();
		assertThat(node.asString()).isEqualTo(STORED_UUID.toString());
	}

	@Test
	@DisplayName("bytea reads back as a base64 string")
	void readsByteaAsBase64String() throws Exception {
		JsonNode node = getFeature(filledFid).json().get("properties").get("byteacol");

		assertThat(node.isString()).isTrue();
		assertThat(node.asString()).isEqualTo(Base64.getEncoder().encodeToString(STORED_BYTES));
	}

	@Test
	@DisplayName("every column type reads back as JSON null when the value is SQL NULL")
	void readsNullForEveryColumnType() throws Exception {
		JsonNode properties = getFeature(nullFid).json().get("properties");

		for (String column : ALL_COLUMNS) {
			assertThat(properties.get(column).isNull()).as("column %s", column).isTrue();
		}
	}

	@Test
	@DisplayName("time reads back as a plain ISO time, independent of the server's time zone")
	void readsTimeAsAPlainIsoTime() throws Exception {
		JsonNode node = getFeature(filledFid).json().get("properties").get("timecol");

		// Unlike date, this one needed no fix on the read side: java.sql.Time also
		// extends java.util.Date, but Jackson's handling of it formats the time of day
		// directly rather than going through the "midnight in the JVM's zone, converted
		// to UTC" instant logic that made date read back wrong. Verified with the server
		// forced to UTC+14 (-Duser.timezone=Pacific/Kiritimati) -- the wire value did not
		// move; measured, not assumed, per the date fix's lesson.
		assertThat(node.isString()).isTrue();
		assertThat(node.asString()).isEqualTo(STORED_TIME.toString());
	}

	// --- writing: the types that work -------------------------------------------------

	@Test
	@DisplayName("text, integer, bigint, double precision, numeric and boolean round-trip verbatim")
	void roundTripsNativeJsonTypesUnchanged() throws Exception {
		String[] columns = { "txt", "intcol", "bigcol", "dblcol", "numcol", "boolcol" };
		JsonNode before = getFeature(filledFid).json().get("properties");

		MockHttpServletResponse response = putProperties(filledFid, rawProperties(before, columns));
		assertThat(response.getStatus()).isEqualTo(200);

		JsonNode after = getFeature(filledFid).json().get("properties");
		assertThat(after.get("txt").asString()).isEqualTo(STORED_TEXT);
		assertThat(after.get("intcol").asInt()).isEqualTo(STORED_INT);
		assertThat(after.get("bigcol").asLong()).isEqualTo(STORED_BIGINT);
		assertThat(after.get("dblcol").asDouble()).isEqualTo(STORED_DOUBLE);
		assertThat(after.get("numcol").asDecimal()).isEqualByComparingTo(STORED_NUMERIC);
		assertThat(after.get("boolcol").asBoolean()).isTrue();
	}

	// --- writing: the types that need conversion ------------------------------------------

	@Test
	@DisplayName("date, timestamptz, uuid, bytea and time round-trip in the exact shape GET returns")
	void roundTripsStringEncodedTypes() throws Exception {
		JsonNode before = getFeature(filledFid).json().get("properties");

		for (String column : List.of("datecol", "tscol", "uuidcol", "byteacol", "timecol")) {
			MockHttpServletResponse response = putProperties(filledFid, rawProperties(before, column));

			// EditService.toColumnValue looks up layer_field.data_type for the column and
			// parses Jackson's generic String into the matching java.time / java.util type
			// before binding it, so JDBC sends a properly typed parameter instead of
			// varchar -- unlike the numeric types and boolean above, these five cannot be
			// bound as whatever plain Java type Jackson produced from the JSON literal.
			assertThat(response.getStatus())
					.as("column %s: writing back exactly what GET produced", column)
					.isEqualTo(200);
		}

		JsonNode after = getFeature(filledFid).json().get("properties");
		assertThat(after.get("datecol")).isEqualTo(before.get("datecol"));
		assertThat(after.get("tscol")).isEqualTo(before.get("tscol"));
		assertThat(after.get("uuidcol")).isEqualTo(before.get("uuidcol"));
		assertThat(after.get("byteacol")).isEqualTo(before.get("byteacol"));
		assertThat(after.get("timecol")).isEqualTo(before.get("timecol"));
	}

	// --- writing: invalid values --------------------------------------------------------

	@Test
	@DisplayName("a string that fails an integer column is a 400 naming the field")
	void rejectsATypeMismatchedIntegerWith400() throws Exception {
		MockHttpServletResponse response = putProperties(filledFid, "{\"intcol\":\"abc\"}");

		// EditService.toColumnValue checks the value against layer_field.data_type before
		// it ever reaches JDBC, so a mismatch is a BadRequestException -- mapped to 400 by
		// ProblemDetailAdvice -- rather than a bare 500 once PostgreSQL refuses the
		// statement. The message names the field by its source_name, the identifier the
		// UI actually shows the user, not the internal column name.
		assertThat(response.getStatus()).isEqualTo(400);
		String body = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).contains("\"title\":\"Ungültige Anfrage\"");
		assertThat(body).as("names the field by its source name").contains("Ganzzahl");
	}

	/**
	 * The reported bug, verbatim: before {@code EditService.asBoundedInteger} existed,
	 * {@code 3000000000} -- past {@link Integer#MAX_VALUE}, so it decodes as a {@link Long}
	 * -- passed straight through {@link Number#intValue()}, which silently wraps instead of
	 * throwing. The value that ended up in the database was {@code -1294967296}, with a
	 * plain 200 OK and nothing anywhere to say the write had not done what it looked like.
	 * Measured against the real endpoint before writing this test, not assumed.
	 */
	@Test
	@DisplayName("an integer value past 32-bit range is a 400 naming the field, not a silently wrapped number")
	void rejectsAnIntegerOverflowWith400NotSilentWraparound() throws Exception {
		MockHttpServletResponse response = putProperties(filledFid, "{\"intcol\":3000000000}");

		assertThat(response.getStatus()).isEqualTo(400);
		String body = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).contains("\"title\":\"Ungültige Anfrage\"");
		assertThat(body).as("names the field by its source name").contains("Ganzzahl");

		Object stored = jdbc.sql("SELECT intcol FROM " + table() + " WHERE fid = :fid")
				.param("fid", filledFid).query().singleRow().get("intcol");
		assertThat(stored).as("must keep its original value, not a wrapped one").isEqualTo(STORED_INT);
	}

	/**
	 * The {@code bigint} counterpart: {@code 9223372036854775808} is {@link Long#MAX_VALUE}
	 * plus one, so it decodes as a {@link java.math.BigInteger} -- Jackson's fallback once a
	 * JSON integer no longer fits even a {@code long}. {@link Number#longValue()} on that
	 * wraps the same way {@code intValue()} does, to {@link Long#MIN_VALUE} measured against
	 * the real endpoint. {@code bigint} looks unreachable by an "everyday" typo the way
	 * {@code integer} is, but it is the one column type on this endpoint no client-side
	 * numeric type can even represent without already having lost precision, which makes it
	 * an easy value to construct without ever intending to overflow anything.
	 */
	@Test
	@DisplayName("a bigint value past 64-bit range is a 400 naming the field, not a silently wrapped number")
	void rejectsABigintOverflowWith400NotSilentWraparound() throws Exception {
		MockHttpServletResponse response = putProperties(filledFid, "{\"bigcol\":9223372036854775808}");

		assertThat(response.getStatus()).isEqualTo(400);
		String body = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).contains("\"title\":\"Ungültige Anfrage\"");
		assertThat(body).as("names the field by its source name").contains("Große Zahl");

		Object stored = jdbc.sql("SELECT bigcol FROM " + table() + " WHERE fid = :fid")
				.param("fid", filledFid).query().singleRow().get("bigcol");
		assertThat(((Number) stored).longValue())
				.as("must keep its original value, not a wrapped one").isEqualTo(STORED_BIGINT);
	}

	/**
	 * A second, independently reachable route to the exact same failure class, found by a
	 * later review of {@link #rejectsABigintOverflowWith400NotSilentWraparound}'s own fix:
	 * {@code 1e30} decodes as a {@link Double}, not a {@link java.math.BigInteger}, since it
	 * has an exponent rather than being a bare integer literal. {@link Double#longValue()}
	 * does not wrap the way {@code int} narrowing does -- it clamps to {@link
	 * Long#MAX_VALUE}, which happens to be exactly {@code bigint}'s own upper bound, so a
	 * range check built on the already-clamped {@code long} let it through unchecked.
	 * {@code integer}/{@code smallint} never showed this, because the clamp value sits well
	 * outside their tighter ranges either way -- only {@code bigint}'s bound coincides with
	 * the clamp itself. None of the three tests above used a decimal point or exponent, so
	 * all three took the safe path; this one deliberately does not. Measured against the
	 * real endpoint before writing this test, not assumed.
	 */
	@Test
	@DisplayName("a bigint value in exponential notation past 64-bit range is a 400, not a value clamped to Long.MAX_VALUE")
	void rejectsABigintOverflowInExponentialNotationWith400() throws Exception {
		MockHttpServletResponse response = putProperties(filledFid, "{\"bigcol\":1e30}");

		assertThat(response.getStatus()).isEqualTo(400);
		String body = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).contains("\"title\":\"Ungültige Anfrage\"");
		assertThat(body).as("names the field by its source name").contains("Große Zahl");

		Object stored = jdbc.sql("SELECT bigcol FROM " + table() + " WHERE fid = :fid")
				.param("fid", filledFid).query().singleRow().get("bigcol");
		assertThat(((Number) stored).longValue())
				.as("must keep its original value, not one clamped to Long.MAX_VALUE")
				.isEqualTo(STORED_BIGINT);
	}

	/**
	 * {@code smallint} is a different shape of the same gap: before this fix it shared
	 * {@code integer}'s {@code intValue()} conversion, so a value like {@code 40000} --
	 * outside {@code smallint}'s real range but a perfectly ordinary {@code int} -- was
	 * never wrapped, only handed to PostgreSQL as-is. PostgreSQL already rejected it (
	 * {@code numeric field overflow}, the {@code ProblemDetailAdvice} handler this package
	 * added earlier), so this was never silent data corruption the way {@code integer}/
	 * {@code bigint} were -- but the rejection carried no field name, because
	 * {@code ProblemDetailAdvice} cannot know which column of a multi-column statement
	 * overflowed. Catching it here, before the statement is even built, recovers that name.
	 *
	 * <p>Its own layer with a dedicated {@code smallint} column: the shared fixture this
	 * class otherwise uses has none.
	 */
	@Test
	@DisplayName("a smallint value past 16-bit range is a 400 naming the field")
	void rejectsASmallintOverflowWith400NamingTheField() throws Exception {
		Project smallintProject = projectRepository.saveAndFlush(
				new Project("Smallint-Test " + UUID.randomUUID(), null, 25832, "osm"));
		UUID smallintLayerId = UUID.randomUUID();
		String smallintTableName = SqlIdentifier.tableName(smallintLayerId);
		try {
			jdbc.sql("""
					CREATE TABLE %s (
					    fid      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
					    geom     geometry(MultiPolygon, 25832) NOT NULL,
					    smallcol smallint
					)
					""".formatted(SqlIdentifier.quoteLayerTable(smallintTableName))).update();
			Layer smallintLayer = layerRepository.saveAndFlush(new Layer(
					smallintLayerId, smallintProject, "Smallint", smallintTableName, "MULTIPOLYGON", 25832));
			fieldRepository.saveAndFlush(new LayerField(smallintLayer, "Kleine Zahl", "smallcol", "smallint", 0));

			long fid = jdbc.sql("INSERT INTO " + SqlIdentifier.quoteLayerTable(smallintTableName)
							+ " (geom) VALUES (ST_Multi(ST_MakeEnvelope(0, 0, 10, 10, 25832))) RETURNING fid")
					.query(Long.class)
					.single();

			MockHttpServletResponse response = mockMvc.perform(post(
							"/api/layers/" + smallintLayer.getId() + "/edits")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"updates\":[{\"fid\":" + fid + ",\"properties\":{\"smallcol\":40000}}]}"))
					.andReturn().getResponse();

			assertThat(response.getStatus()).isEqualTo(400);
			String body = response.getContentAsString(StandardCharsets.UTF_8);
			assertThat(body).contains("\"title\":\"Ungültige Anfrage\"");
			assertThat(body).as("names the field by its source name").contains("Kleine Zahl");
		}
		finally {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(smallintTableName)).update();
			layerRepository.findById(smallintLayerId).ifPresent(layerRepository::delete);
			projectRepository.deleteById(smallintProject.getId());
		}
	}

	/**
	 * PostgreSQL rejects a signed {@code NaN} outright ({@code invalid input syntax for
	 * type numeric}), unlike the four signed spellings of Infinity {@link
	 * #acceptsEverySignedSpellingOfInfinity} below confirms all work. A review of an
	 * earlier version of {@code EditService.SPECIAL_NUMERIC_LITERAL} found it did not draw
	 * that line -- its sign applied to the whole {@code nan|infinity} alternation, so
	 * {@code "+NaN"}/{@code "-NaN"} were wrapped and handed to PostgreSQL's own {@code
	 * CAST}, which then failed with no {@link de.kreuter.hgis.common.BadRequestException}
	 * to translate it: a bare 500, in place of the clean 400 every other unparsable number
	 * on this endpoint gets, {@code "abc"} included.
	 */
	@Test
	@DisplayName("+NaN and -NaN are a 400 naming the field, not a raw 500 from PostgreSQL's own rejection")
	void rejectsASignedNanWith400NotA500() throws Exception {
		for (String signedNan : List.of("+NaN", "-NaN")) {
			MockHttpServletResponse response = putProperties(filledFid, "{\"numcol\":\"" + signedNan + "\"}");

			assertThat(response.getStatus())
					.as("%s must get the same clean 400 an ordinary unparsable number does", signedNan)
					.isEqualTo(400);
			String body = response.getContentAsString(StandardCharsets.UTF_8);
			assertThat(body).contains("\"title\":\"Ungültige Anfrage\"");
			assertThat(body).as("names the field by its source name").contains("Betrag");
		}
	}

	/**
	 * The counterpart to {@link #rejectsASignedNanWith400NotA500}: tightening the pattern
	 * to exclude a signed NaN must not also, by accident, exclude one of the four signed
	 * spellings of Infinity PostgreSQL does accept.
	 *
	 * <p>Deliberately its own layer with a plain, unconstrained {@code numeric} column,
	 * not {@link #filledFid}'s {@code numcol numeric(12,2)}: a scale-constrained column
	 * rejects every spelling of Infinity outright with its own, unrelated {@code numeric
	 * field overflow} (Infinity fits no finite precision/scale, unlike NaN, which that
	 * constraint exempts) -- a pre-existing gap this method is not about and must not be
	 * mistaken for a failure of the pattern under test here.
	 */
	@Test
	@DisplayName("all four signed spellings of Infinity are still accepted")
	void acceptsEverySignedSpellingOfInfinity() throws Exception {
		Project infProject = projectRepository.saveAndFlush(
				new Project("Vorzeichen-Unendlich-Test " + UUID.randomUUID(), null, 25832, "osm"));
		UUID infLayerId = UUID.randomUUID();
		String infTableName = SqlIdentifier.tableName(infLayerId);
		try {
			jdbc.sql("""
					CREATE TABLE %s (
					    fid    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
					    geom   geometry(MultiPolygon, 25832) NOT NULL,
					    numcol numeric
					)
					""".formatted(SqlIdentifier.quoteLayerTable(infTableName))).update();
			Layer infLayer = layerRepository.saveAndFlush(
					new Layer(infLayerId, infProject, "Vorzeichen-Unendlich", infTableName, "MULTIPOLYGON", 25832));
			fieldRepository.saveAndFlush(new LayerField(infLayer, "Betrag", "numcol", "numeric", 0));

			for (String spelling : List.of("+Inf", "-Inf", "+Infinity", "-Infinity")) {
				long fid = jdbc.sql("INSERT INTO " + SqlIdentifier.quoteLayerTable(infTableName)
								+ " (geom) VALUES (ST_Multi(ST_MakeEnvelope(0, 0, 10, 10, 25832))) RETURNING fid")
						.query(Long.class)
						.single();

				MockHttpServletResponse response = mockMvc.perform(post(
								"/api/layers/" + infLayer.getId() + "/edits")
								.contentType(MediaType.APPLICATION_JSON)
								.content("{\"updates\":[{\"fid\":" + fid + ",\"properties\":"
										+ "{\"numcol\":\"" + spelling + "\"}}]}"))
						.andReturn().getResponse();
				assertThat(response.getStatus())
						.as("%s must still be accepted -- PostgreSQL itself allows it", spelling)
						.isEqualTo(200);

				// PostgreSQL normalises every signed spelling to "Infinity"/"-Infinity" on
				// readback, and Jackson writes a non-finite double as a quoted JSON string
				// rather than the bare, invalid JSON token -- so this is a plain string
				// comparison, not a numeric one.
				MockHttpServletResponse getResponse = mockMvc.perform(
								get("/api/layers/" + infLayer.getId() + "/features/" + fid))
						.andReturn().getResponse();
				JsonNode numcol = JSON.readTree(getResponse.getContentAsString(StandardCharsets.UTF_8))
						.get("properties").get("numcol");
				String expected = spelling.startsWith("-") ? "-Infinity" : "Infinity";
				assertThat(numcol.asString()).as("%s reads back normalised", spelling).isEqualTo(expected);
			}
		}
		finally {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(infTableName)).update();
			layerRepository.findById(infLayerId).ifPresent(layerRepository::delete);
			projectRepository.deleteById(infProject.getId());
		}
	}

	/**
	 * Unlike every other test in this section, this value is never rejected by {@code
	 * EditService} itself: {@code new BigDecimal("123456789012345.67")} parses fine, and
	 * nothing in this codebase knows {@code numcol}'s precision and scale ahead of time to
	 * check it against. The rejection is PostgreSQL's own, at the {@code INSERT}/{@code
	 * UPDATE} -- {@code numeric field overflow}, SQLSTATE 22003 -- so this is a test of
	 * {@code ProblemDetailAdvice.handleDataIntegrityViolation}, not of {@code EditService}.
	 * A review found this uncaught: an everyday typo or a wrongly-scaled import value hits
	 * it far more often than a special value like NaN ever would, and it used to fall
	 * through to the generic 500 with neither a field named nor a reason given.
	 */
	@Test
	@DisplayName("a numeric value too large for its column's precision/scale is a 400, not a raw 500")
	void rejectsANumericOverflowWith400NotA500() throws Exception {
		MockHttpServletResponse response = putProperties(filledFid, "{\"numcol\":123456789012345.67}");

		assertThat(response.getStatus())
				.as("an overflowing numeric value must be a clean 400, not PostgreSQL's own "
						+ "\"numeric field overflow\" surfacing as an unhandled 500")
				.isEqualTo(400);
		String body = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).contains("\"title\":\"Ungültige Anfrage\"");
	}

	@Test
	@DisplayName("an unparsable date is a 400 naming the field, not a generic 500")
	void rejectsATypeMismatchedDateWith400() throws Exception {
		MockHttpServletResponse response = putProperties(filledFid, "{\"datecol\":\"kein-datum\"}");

		assertThat(response.getStatus()).isEqualTo(400);
		String body = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).contains("\"title\":\"Ungültige Anfrage\"");
		assertThat(body).as("names the field by its source name").contains("Datum");
	}

	@Test
	@DisplayName("an unparsable time is a 400 naming the field, not a generic 500")
	void rejectsATypeMismatchedTimeWith400() throws Exception {
		MockHttpServletResponse response = putProperties(filledFid, "{\"timecol\":\"keine-uhrzeit\"}");

		assertThat(response.getStatus()).isEqualTo(400);
		String body = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).contains("\"title\":\"Ungültige Anfrage\"");
		assertThat(body).as("names the field by its source name").contains("Uhrzeit");
	}

	// --- writing: null vs. absent --------------------------------------------------------

	@Test
	@DisplayName("an explicit null clears the column; a key that is absent leaves it untouched")
	void nullClearsAColumnAndOmissionLeavesOthersAlone() throws Exception {
		// A row of its own so this write cannot be observed by, or depend on, the order
		// the other tests in this class happen to run in.
		long fid = insertFilledRow();

		MockHttpServletResponse response = putProperties(fid, "{\"txt\":null}");
		assertThat(response.getStatus()).isEqualTo(200);

		JsonNode after = getFeature(fid).json().get("properties");
		assertThat(after.get("txt").isNull()).as("the mentioned key is cleared").isTrue();
		assertThat(after.get("intcol").asInt())
				.as("a key the update never mentioned keeps its value")
				.isEqualTo(STORED_INT);
		assertThat(after.get("bigcol").asLong()).isEqualTo(STORED_BIGINT);
		assertThat(after.get("boolcol").asBoolean()).isTrue();
	}

	// --- fixture -----------------------------------------------------------------------

	private static final List<String> ALL_COLUMNS = List.of("txt", "intcol", "bigcol", "dblcol",
			"numcol", "boolcol", "datecol", "tscol", "uuidcol", "byteacol", "timecol");

	private String table() {
		return SqlIdentifier.quoteLayerTable(tableName);
	}

	private long insertFilledRow() {
		return insertRow(STORED_TEXT, STORED_INT, STORED_BIGINT, STORED_DOUBLE, STORED_NUMERIC,
				true, STORED_DATE.toString(), STORED_TIMESTAMP.toString(), STORED_UUID.toString(),
				STORED_BYTES, STORED_TIME.toString());
	}

	private long insertRow(String txt, Integer intcol, Long bigcol, Double dblcol,
			BigDecimal numcol, Boolean boolcol, String datecol, String tscol, String uuidcol,
			byte[] byteacol, String timecol) {
		return jdbc.sql("INSERT INTO " + table() + " (geom, txt, intcol, bigcol, dblcol, numcol, "
						+ "boolcol, datecol, tscol, uuidcol, byteacol, timecol) VALUES ("
						+ "ST_Multi(ST_SetSRID(ST_MakePoint(10, 53), 4326)), :txt, :intcol, :bigcol, "
						+ ":dblcol, :numcol, :boolcol, CAST(:datecol AS date), "
						+ "CAST(:tscol AS timestamptz), CAST(:uuidcol AS uuid), :byteacol, "
						+ "CAST(:timecol AS time)) "
						+ "RETURNING fid")
				.param("txt", txt)
				.param("intcol", intcol)
				.param("bigcol", bigcol)
				.param("dblcol", dblcol)
				.param("numcol", numcol)
				.param("boolcol", boolcol)
				.param("datecol", datecol)
				.param("tscol", tscol)
				.param("uuidcol", uuidcol)
				.param("byteacol", byteacol)
				.param("timecol", timecol)
				.query(Long.class).single();
	}

	// --- HTTP helpers --------------------------------------------------------------------

	private record FeatureResponse(String raw, JsonNode json) {
	}

	private String featureUrl(long fid) {
		return "/api/layers/" + layer.getId() + "/features/" + fid;
	}

	private String editsUrl() {
		return "/api/layers/" + layer.getId() + "/edits";
	}

	private FeatureResponse getFeature(long fid) throws Exception {
		MockHttpServletResponse response = mockMvc.perform(get(featureUrl(fid))).andReturn().getResponse();
		assertThat(response.getStatus()).as("GET %s", featureUrl(fid)).isEqualTo(200);
		String raw = response.getContentAsString(StandardCharsets.UTF_8);
		return new FeatureResponse(raw, JSON.readTree(raw));
	}

	private MockHttpServletResponse putProperties(long fid, String propertiesJson) throws Exception {
		String body = "{\"updates\":[{\"fid\":" + fid + ",\"properties\":" + propertiesJson + "}]}";
		return mockMvc.perform(post(editsUrl())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andReturn().getResponse();
	}

	/** Builds {@code {"col":<raw value from GET>, ...}}, verbatim, for the given columns. */
	private static String rawProperties(JsonNode properties, String... columns) {
		StringBuilder json = new StringBuilder("{");
		for (int i = 0; i < columns.length; i++) {
			if (i > 0) {
				json.append(',');
			}
			json.append('"').append(columns[i]).append("\":").append(properties.get(columns[i]).toString());
		}
		return json.append('}').toString();
	}
}
