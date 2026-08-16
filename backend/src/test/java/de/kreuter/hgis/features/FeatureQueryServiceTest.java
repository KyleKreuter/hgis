package de.kreuter.hgis.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.dto.FeatureDtos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Feature queries against a real PostGIS table.
 *
 * The fixture is deliberately awkward: duplicated sort values and NULLs, because those
 * are the two things keyset paging gets wrong. A layer where every value is distinct
 * would pass with a broken cursor.
 *
 * Built once for the class ({@link TestInstance.Lifecycle#PER_CLASS}) -- nothing here
 * writes, so there is nothing to isolate between methods.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeatureQueryServiceTest {

	@Autowired
	private FeatureQueryService service;

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

	/** 12 rows: four share a street name, three have none at all. */
	private static final List<String[]> ROWS = List.of(
			new String[] { "Alsterufer", "10" },
			new String[] { "Alsterufer", "20" },
			new String[] { "Alsterufer", "30" },
			new String[] { "Alsterufer", "40" },
			new String[] { "Böhmkenstraße", "15" },
			new String[] { "Große Elbstraße", "25" },
			new String[] { "Müllerstraße", "35" },
			new String[] { "Rödingsmarkt", "45" },
			new String[] { "Ölmühle", "55" },
			new String[] { null, "60" },
			new String[] { null, "70" },
			new String[] { null, null });

	@BeforeAll
	void createLayer() {
		project = projectRepository.saveAndFlush(
				new Project("Feature-Test " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom    geometry(MultiPolygon, 25832) NOT NULL,
				    strasse text,
				    hoehe   double precision
				)
				""".formatted(table)).update();

		int index = 0;
		for (String[] row : ROWS) {
			// Spread the geometries along a line so a bbox can select a known prefix.
			double x = 550000 + index * 100;
			jdbc.sql("INSERT INTO " + table + " (geom, strasse, hoehe) VALUES ("
							+ "ST_Multi(ST_MakeEnvelope(:x, 5930000, :x2, 5930100, 25832)), :s, :h)")
					.param("x", x)
					.param("x2", x + 50)
					.param("s", row[0])
					.param("h", row[1] == null ? null : Double.parseDouble(row[1]))
					.update();
			index++;
		}

		Layer newLayer = new Layer(layerId, project, "Adressen", tableName, "MULTIPOLYGON", 25832);
		newLayer.setFeatureCount(ROWS.size());
		layer = layerRepository.saveAndFlush(newLayer);

		fieldRepository.saveAndFlush(new LayerField(layer, "Straße", "strasse", "text", 0));
		fieldRepository.saveAndFlush(new LayerField(layer, "Höhe", "hoehe", "double precision", 1));
	}

	@AfterAll
	void dropLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.deleteById(layer.getId());
		projectRepository.deleteById(project.getId());
	}

	private Project modeProject;
	private Layer modeLayer;
	private String modeTableName;

	/** The rectangle every selection-mode test queries against, in EPSG:4326. */
	private static final double[] SELECTION_RECT = { 10.00, 50.00, 10.10, 50.10 };

	/**
	 * A second, small fixture dedicated to the {@code intersects}/{@code contains}
	 * selection modes. The paging fixture above is deliberately made of equal,
	 * non-overlapping envelopes along a line -- useful for cursor tests, useless for
	 * proving that {@code &&} alone is not enough to select by geometry.
	 *
	 * <p>This layer's storage CRS is EPSG:4326, so {@link #SELECTION_RECT} can be written
	 * directly in the same coordinates as the fixture rows, without a mental detour
	 * through UTM to check which one lies where.
	 */
	@BeforeAll
	void createModeLayer() {
		modeProject = projectRepository.saveAndFlush(
				new Project("Auswahl-Test " + UUID.randomUUID(), null, 4326, "osm"));

		UUID layerId = UUID.randomUUID();
		modeTableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(modeTableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom  geometry(MultiPolygon, 4326) NOT NULL,
				    label text
				)
				""".formatted(table)).update();

		jdbc.sql("""
				INSERT INTO %s (geom, label) VALUES
				-- Fully inside SELECTION_RECT: found by both intersects and contains.
				(ST_Multi(ST_MakeEnvelope(10.02, 50.02, 10.04, 50.04, 4326)), 'innen'),
				-- Straddles the rectangle's right edge (10.10): intersects it, is not
				-- fully contained by it.
				(ST_Multi(ST_MakeEnvelope(10.08, 50.03, 10.15, 50.05, 4326)), 'kante'),
				-- An L made of two arms that individually never reach the rectangle. Their
				-- combined envelope overlaps it -- the && prefilter alone would wrongly
				-- include this row -- but neither arm actually touches it, so both exact
				-- modes must exclude it.
				(ST_Multi(ST_Collect(ARRAY[
				    ST_MakeEnvelope(10.15, 50.05, 10.20, 50.20, 4326),
				    ST_MakeEnvelope(10.05, 50.15, 10.20, 50.20, 4326)
				])), 'nur-bbox')
				""".formatted(table)).update();

		Layer newLayer = new Layer(layerId, modeProject, "Auswahl", modeTableName, "MULTIPOLYGON", 4326);
		newLayer.setFeatureCount(3);
		modeLayer = layerRepository.saveAndFlush(newLayer);

		fieldRepository.saveAndFlush(new LayerField(modeLayer, "Label", "label", "text", 0));
	}

	@AfterAll
	void dropModeLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(modeTableName)).update();
		layerRepository.deleteById(modeLayer.getId());
		projectRepository.deleteById(modeProject.getId());
	}

	private Project searchProject;
	private Layer searchLayer;
	private String searchTableName;

	/**
	 * A fixture dedicated to {@code search} and the fid endpoint: two text fields so a
	 * multi-field OR is actually exercised, a numeric field so an accidental ILIKE against
	 * it would surface as a database type error rather than passing quietly, and values
	 * chosen to make {@code %} and {@code _} escaping provable rather than assumed.
	 */
	@BeforeAll
	void createSearchLayer() {
		searchProject = projectRepository.saveAndFlush(
				new Project("Suche-Test " + UUID.randomUUID(), null, 4326, "osm"));

		UUID layerId = UUID.randomUUID();
		searchTableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(searchTableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom   geometry(MultiPoint, 4326) NOT NULL,
				    name   text,
				    ort    text,
				    nummer integer
				)
				""".formatted(table)).update();

		// A literal "%" -- must be found by a search for "50%", and must not turn the
		// search into a pattern that also swallows unrelated rows.
		insertSearchRow(table, "50%", null, 1);
		// Distinct from the row above only by the trailing "%" -- proves the escaping is
		// exact, not just "somehow narrower".
		insertSearchRow(table, "50", null, 2);
		// A literal "_" -- must be found by a search for "5_", and only by that search.
		insertSearchRow(table, "5_x", null, 3);
		// Matched through the first text field.
		insertSearchRow(table, "Schmidt", "Hamburg", 50);
		// Matched through the second text field, and case-differently, proving both the
		// multi-field OR and the case-insensitivity.
		insertSearchRow(table, "Müller", "SCHMIDTplatz", 99);
		// Same nummer as the "Schmidt" row above, but no text match -- the row that tells
		// filter+search AND apart from filter+search OR.
		insertSearchRow(table, null, null, 50);
		// An apostrophe -- proves the term travels as a bind parameter rather than being
		// concatenated into the SQL text.
		insertSearchRow(table, "O'Brien", null, 8);
		// A literal backslash -- TextSearch doubles it before ESCAPE '\' sees it, so this
		// must match without PostgreSQL ever seeing a dangling escape character.
		insertSearchRow(table, "C:\\Windows", null, 9);
		// "777" appears only in nummer, never in a text field -- a search for "777" that
		// found this row would mean the numeric column was searched too.
		insertSearchRow(table, "Kein Treffer", "Andere Stadt", 777);

		Layer newLayer = new Layer(layerId, searchProject, "Suche", searchTableName, "MULTIPOINT", 4326);
		newLayer.setFeatureCount(9);
		searchLayer = layerRepository.saveAndFlush(newLayer);

		fieldRepository.saveAndFlush(new LayerField(searchLayer, "Name", "name", "text", 0));
		fieldRepository.saveAndFlush(new LayerField(searchLayer, "Ort", "ort", "text", 1));
		fieldRepository.saveAndFlush(new LayerField(searchLayer, "Nummer", "nummer", "integer", 2));
	}

	@AfterAll
	void dropSearchLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(searchTableName)).update();
		layerRepository.deleteById(searchLayer.getId());
		projectRepository.deleteById(searchProject.getId());
	}

	private void insertSearchRow(String table, String name, String ort, int nummer) {
		jdbc.sql("INSERT INTO " + table + " (geom, name, ort, nummer) VALUES ("
						+ "ST_Multi(ST_SetSRID(ST_MakePoint(10, 53), 4326)), :name, :ort, :nummer)")
				.param("name", name)
				.param("ort", ort)
				.param("nummer", nummer)
				.update();
	}

	private FeatureQueryService.Query searchQuery(String filter, String search) {
		return new FeatureQueryService.Query(null, false, filter, search, null, null, false, null, 100);
	}

	private List<Object> namesOf(FeatureDtos.Page page) {
		return page.features().stream().map(feature -> feature.properties().get("name")).toList();
	}

	private Project noTextProject;
	private Layer noTextLayer;
	private String noTextTableName;

	/** A layer with no text field at all -- what {@code search} must refuse outright. */
	@BeforeAll
	void createNoTextLayer() {
		noTextProject = projectRepository.saveAndFlush(
				new Project("Ohne-Text-Test " + UUID.randomUUID(), null, 4326, "osm"));

		UUID layerId = UUID.randomUUID();
		noTextTableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(noTextTableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(MultiPoint, 4326) NOT NULL,
				    wert integer
				)
				""".formatted(table)).update();
		jdbc.sql("INSERT INTO " + table + " (geom, wert) VALUES "
				+ "(ST_Multi(ST_SetSRID(ST_MakePoint(10, 53), 4326)), 1)").update();

		Layer newLayer = new Layer(layerId, noTextProject, "Ohne Text", noTextTableName, "MULTIPOINT", 4326);
		newLayer.setFeatureCount(1);
		noTextLayer = layerRepository.saveAndFlush(newLayer);

		fieldRepository.saveAndFlush(new LayerField(noTextLayer, "Wert", "wert", "integer", 0));
	}

	@AfterAll
	void dropNoTextLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(noTextTableName)).update();
		layerRepository.deleteById(noTextLayer.getId());
		projectRepository.deleteById(noTextProject.getId());
	}

	private Project hugeProject;
	private Layer hugeLayer;
	private String hugeTableName;

	/** How many of {@link #hugeLayer}'s rows carry {@code bucket = 1}, the rest carry 2. */
	private static final int HUGE_BUCKET_SIZE = 1_500;

	/** One more than the fid endpoint's upper bound, so the unrestricted query trips it. */
	private static final int HUGE_TOTAL_SIZE = 100_001;

	/**
	 * A layer built with one bulk {@code INSERT ... SELECT generate_series}, not one row
	 * at a time -- the two things this backs are the fid endpoint's upper bound (needs a
	 * layer past 100.000 rows) and proof that it is not secretly capped at a page's worth
	 * of rows (needs a filtered result past 1.000). Real geometries would make either test
	 * slow for no reason the assertions care about.
	 */
	@BeforeAll
	void createHugeLayer() {
		hugeProject = projectRepository.saveAndFlush(
				new Project("Riesig-Test " + UUID.randomUUID(), null, 4326, "osm"));

		UUID layerId = UUID.randomUUID();
		hugeTableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(hugeTableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom   geometry(MultiPoint, 4326) NOT NULL,
				    bucket integer
				)
				""".formatted(table)).update();
		jdbc.sql("INSERT INTO " + table + " (geom, bucket) "
						+ "SELECT ST_Multi(ST_SetSRID(ST_MakePoint(0, 0), 4326)), "
						+ "CASE WHEN gs <= :bucketSize THEN 1 ELSE 2 END "
						+ "FROM generate_series(1, :total) AS gs")
				.param("bucketSize", HUGE_BUCKET_SIZE)
				.param("total", HUGE_TOTAL_SIZE)
				.update();

		Layer newLayer = new Layer(layerId, hugeProject, "Riesig", hugeTableName, "MULTIPOINT", 4326);
		newLayer.setFeatureCount(HUGE_TOTAL_SIZE);
		hugeLayer = layerRepository.saveAndFlush(newLayer);

		fieldRepository.saveAndFlush(new LayerField(hugeLayer, "Bucket", "bucket", "integer", 0));
	}

	@AfterAll
	void dropHugeLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(hugeTableName)).update();
		layerRepository.deleteById(hugeLayer.getId());
		projectRepository.deleteById(hugeProject.getId());
	}

	private Project collidingProject;
	private Layer collidingLayer;
	private String collidingTableName;
	private UUID bigintFieldId;
	private UUID textFieldId;

	/**
	 * The Straßenbaumkataster's shape, reduced to two columns and four rows.
	 *
	 * <p>One field is displayed as "Kronendurchmesser Quelle" and stored in the column
	 * {@code kronendurchmesser}; the next is displayed as "Kronendurchmesser" and stored in
	 * {@code kronendurchmesser_z}. The word "kronendurchmesser" is therefore a display name
	 * and a column name at once, and it used to mean the text column to the filter and the
	 * bigint column to the sort parameter -- on the real layer that was 225.657 rows against
	 * 73.890 for what a user reads as the same question. The values below reproduce the
	 * split in miniature: compared as numbers, one row is over 10; compared as text, three
	 * are.
	 */
	@BeforeAll
	void createCollidingLayer() {
		collidingProject = projectRepository.saveAndFlush(
				new Project("Namensgleich-Test " + UUID.randomUUID(), null, 4326, "osm"));

		UUID layerId = UUID.randomUUID();
		collidingTableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(collidingTableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom                geometry(MultiPoint, 4326) NOT NULL,
				    kronendurchmesser   bigint,
				    kronendurchmesser_z text
				)
				""".formatted(table)).update();
		jdbc.sql("""
				INSERT INTO %s (geom, kronendurchmesser, kronendurchmesser_z) VALUES
				(ST_Multi(ST_SetSRID(ST_MakePoint(10, 53), 4326)), 2, '2'),
				(ST_Multi(ST_SetSRID(ST_MakePoint(10, 53), 4326)), 3, '3'),
				(ST_Multi(ST_SetSRID(ST_MakePoint(10, 53), 4326)), 9, '9'),
				(ST_Multi(ST_SetSRID(ST_MakePoint(10, 53), 4326)), 12, '12')
				""".formatted(table)).update();

		Layer newLayer = new Layer(layerId, collidingProject, "Namensgleich", collidingTableName,
				"MULTIPOINT", 4326);
		newLayer.setFeatureCount(4);
		collidingLayer = layerRepository.saveAndFlush(newLayer);

		// The ids are kept: they are the one identifier that resolves for both of these
		// fields, and the tests below spend them on exactly that.
		bigintFieldId = fieldRepository.saveAndFlush(new LayerField(collidingLayer,
				"Kronendurchmesser Quelle", "kronendurchmesser", "bigint", 0)).getId();
		textFieldId = fieldRepository.saveAndFlush(new LayerField(collidingLayer,
				"Kronendurchmesser", "kronendurchmesser_z", "text", 1)).getId();
	}

	@AfterAll
	void dropCollidingLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(collidingTableName)).update();
		layerRepository.deleteById(collidingLayer.getId());
		projectRepository.deleteById(collidingProject.getId());
	}

	private Project typeProject;
	private Layer typeLayer;
	private String typeTableName;

	/**
	 * The three bigint values, in the order they are inserted -- see
	 * {@link #pagesByABigintColumn}.
	 *
	 * <p>All past 2^53, where doubles count in twos: the middle one is exactly halfway
	 * between its neighbours and rounds up onto the largest, while the other two survive a
	 * double unchanged. Inserted largest first, so their ascending order is the reverse of
	 * their fid order -- without that the fid tie-breaker quietly absorbs the rounding and
	 * the walk comes out right for the wrong reason.
	 */
	private static final List<Long> HUGE_NUMBERS =
			List.of(9_007_199_254_740_996L, 9_007_199_254_740_995L, 9_007_199_254_740_994L);

	/**
	 * The three numeric values, in the order they are inserted -- see
	 * {@link #pagesByANumericColumn}.
	 *
	 * <p>Twenty significant digits, of which a double holds fifteen: all three round to one
	 * and the same double. Inserted largest first, so their ascending order is the reverse
	 * of their fid order -- which is what turns the rounding into missing rows rather than
	 * into a tie the fid quietly breaks.
	 */
	private static final List<BigDecimal> EXACT_NUMBERS = List.of(
			new BigDecimal("1234567890.1234567893"),
			new BigDecimal("1234567890.1234567892"),
			new BigDecimal("1234567890.1234567891"));

	/**
	 * A layer built out of the column types the query service used to break on.
	 *
	 * <p>Three of them had no cast when a cursor or a filter value was bound, and PostgreSQL
	 * has no operator between them and the {@code varchar} a bound string arrives as, so
	 * sorting or filtering by such a column answered 500 rather than rows. The other two
	 * failed more quietly, both because a cursor carried its value as a JSON double:
	 * {@code bigint} has 64 bits where a double holds 53, and {@code numeric} is exact where
	 * no JSON number is.
	 *
	 * <p>Only three rows, and one page size of one throughout: what is being tested is
	 * whether a page boundary lands correctly, and every row here is a boundary.
	 */
	@BeforeAll
	void createTypeLayer() {
		typeProject = projectRepository.saveAndFlush(
				new Project("Typen-Test " + UUID.randomUUID(), null, 4326, "osm"));

		UUID layerId = UUID.randomUUID();
		typeTableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(typeTableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom      geometry(MultiPoint, 4326) NOT NULL,
				    zeit      time,
				    kennung   uuid,
				    rohdaten  bytea,
				    grosszahl bigint,
				    messwert  numeric(30,10)
				)
				""".formatted(table)).update();

		jdbc.sql("""
				INSERT INTO %s (geom, zeit, kennung, rohdaten, grosszahl, messwert) VALUES
				(ST_Multi(ST_SetSRID(ST_MakePoint(10, 53), 4326)), '08:15:00',
				 '00000000-0000-0000-0000-000000000001', '\\x01', :first, :firstExact),
				-- With milliseconds on purpose: a time whose cursor is written without them
				-- lands before the row it was taken from, and the next page starts by
				-- repeating that row instead of moving past it.
				(ST_Multi(ST_SetSRID(ST_MakePoint(10, 53), 4326)), '12:30:45.123',
				 '00000000-0000-0000-0000-000000000002', '\\x02', :second, :secondExact),
				(ST_Multi(ST_SetSRID(ST_MakePoint(10, 53), 4326)), '18:45:30',
				 '00000000-0000-0000-0000-000000000003', '\\x03', :third, :thirdExact)
				""".formatted(table))
				.param("first", HUGE_NUMBERS.get(0))
				.param("second", HUGE_NUMBERS.get(1))
				.param("third", HUGE_NUMBERS.get(2))
				.param("firstExact", EXACT_NUMBERS.get(0))
				.param("secondExact", EXACT_NUMBERS.get(1))
				.param("thirdExact", EXACT_NUMBERS.get(2))
				.update();

		Layer newLayer = new Layer(layerId, typeProject, "Typen", typeTableName, "MULTIPOINT", 4326);
		newLayer.setFeatureCount(3);
		typeLayer = layerRepository.saveAndFlush(newLayer);

		fieldRepository.saveAndFlush(new LayerField(typeLayer, "Zeit", "zeit", "time", 0));
		fieldRepository.saveAndFlush(new LayerField(typeLayer, "Kennung", "kennung", "uuid", 1));
		fieldRepository.saveAndFlush(new LayerField(typeLayer, "Rohdaten", "rohdaten", "bytea", 2));
		fieldRepository.saveAndFlush(new LayerField(typeLayer, "Großzahl", "grosszahl", "bigint", 3));
		fieldRepository.saveAndFlush(new LayerField(typeLayer, "Messwert", "messwert", "numeric", 4));
	}

	@AfterAll
	void dropTypeLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(typeTableName)).update();
		layerRepository.deleteById(typeLayer.getId());
		projectRepository.deleteById(typeProject.getId());
	}

	/** Walks the type layer one row per page and returns one column, in the order delivered. */
	private List<Object> pageThroughTypeLayer(String sort, String column) {
		List<Object> collected = new ArrayList<>();
		String cursor = null;
		int guard = 0;

		do {
			FeatureDtos.Page page = service.list(typeLayer.getId(),
					new FeatureQueryService.Query(sort, false, null, null, null, null, false, cursor, 1));
			page.features().forEach(feature -> collected.add(feature.properties().get(column)));
			cursor = page.nextCursor();
		}
		while (cursor != null && ++guard < 10);

		return collected;
	}

	private FeatureQueryService.Query typeQuery(String filter) {
		return new FeatureQueryService.Query(null, false, filter, null, null, null, false, null, 100);
	}

	private FeatureQueryService.Query modeQuery(String mode) {
		return new FeatureQueryService.Query(
				null, false, null, null, SELECTION_RECT, mode, false, null, 100);
	}

	private List<Object> labelsOf(FeatureDtos.Page page) {
		return page.features().stream().map(feature -> feature.properties().get("label")).toList();
	}

	private FeatureQueryService.Query query(String sort, boolean desc, String cursor, int size) {
		return new FeatureQueryService.Query(sort, desc, null, null, null, null, false, cursor, size);
	}

	/** Walks every page and returns the values of one column, in the order delivered. */
	private List<Object> pageThrough(String sort, boolean desc, int pageSize, String column) {
		List<Object> collected = new ArrayList<>();
		String cursor = null;
		int guard = 0;

		do {
			FeatureDtos.Page page = service.list(layer.getId(), query(sort, desc, cursor, pageSize));
			page.features().forEach(feature -> collected.add(feature.properties().get(column)));
			cursor = page.nextCursor();
		}
		while (cursor != null && ++guard < 50);

		return collected;
	}

	@Test
	@DisplayName("paging by fid returns every row exactly once")
	void pagesThroughEveryRow() {
		List<Object> all = pageThrough(null, false, 5, "strasse");

		assertThat(all).hasSize(ROWS.size());
	}

	@Test
	@DisplayName("a page size that divides the total evenly does not produce a phantom page")
	void stopsCleanlyOnAnExactMultiple() {
		// 12 rows in pages of 4: the third page fills exactly, and only fetching one row
		// beyond the page tells us there is nothing after it.
		FeatureDtos.Page third = service.list(layer.getId(),
				query(null, false, service.list(layer.getId(),
						query(null, false, service.list(layer.getId(), query(null, false, null, 4))
								.nextCursor(), 4)).nextCursor(), 4));

		assertThat(third.features()).hasSize(4);
		assertThat(third.nextCursor()).as("no fourth page").isNull();
	}

	@Test
	@DisplayName("duplicate sort values do not make rows repeat or vanish across pages")
	void handlesDuplicateSortValues() {
		// Four rows share "Alsterufer". Without fid as tie-breaker, a page boundary
		// falling inside that block would either skip rows or serve them twice.
		List<Object> paged = pageThrough("Straße", false, 2, "hoehe");
		List<Object> single = pageThrough("Straße", false, 100, "hoehe");

		assertThat(paged).hasSize(ROWS.size());
		assertThat(paged).containsExactlyElementsOf(single);
	}

	@Test
	@DisplayName("NULLs sort last in both directions and page correctly")
	void handlesNullsInTheSortColumn() {
		List<Object> ascending = pageThrough("Straße", false, 3, "strasse");
		List<Object> descending = pageThrough("Straße", true, 3, "strasse");

		assertThat(ascending).hasSize(ROWS.size());
		assertThat(descending).hasSize(ROWS.size());
		assertThat(ascending.subList(ascending.size() - 3, ascending.size()))
				.as("three NULL rows at the end, ascending")
				.containsOnlyNulls();
		assertThat(descending.subList(descending.size() - 3, descending.size()))
				.as("NULLS LAST applies descending too, so NULLs never lead a page")
				.containsOnlyNulls();

		// Compared as a reversal rather than against named streets: where "Ölmühle" lands
		// is decided by the database collation, not by this code. The property that
		// actually matters -- one direction is the exact mirror of the other -- holds
		// under any collation.
		List<Object> ascendingNames = ascending.stream().filter(Objects::nonNull).toList();
		List<Object> descendingNames = descending.stream().filter(Objects::nonNull).toList();
		assertThat(descendingNames)
				.containsExactlyElementsOf(ascendingNames.reversed());
		assertThat(ascendingNames.get(0)).isEqualTo("Alsterufer");
	}

	@Test
	@DisplayName("sorting descending by a numeric column with NULLs stays complete")
	void handlesNumericSort() {
		List<Object> values = pageThrough("Höhe", true, 4, "hoehe");

		assertThat(values).hasSize(ROWS.size());
		assertThat(values.get(0)).isEqualTo(70.0);
		assertThat(values.get(values.size() - 1)).as("the single NULL height comes last").isNull();
	}

	@Test
	@DisplayName("sorting by fid alone still honours the direction")
	void pagesDescendingByFid() {
		// "Newest first" is this query, and silently serving the opposite would be the
		// kind of wrong nobody checks.
		List<Object> descending = pageThrough("fid", true, 5, "hoehe");
		List<Object> ascending = pageThrough("fid", false, 5, "hoehe");

		assertThat(descending).hasSize(ROWS.size());
		assertThat(descending).containsExactlyElementsOf(ascending.reversed());
	}

	@Test
	@DisplayName("the total is reported once, on the first page")
	void reportsTheTotalOnlyOnTheFirstPage() {
		FeatureDtos.Page first = service.list(layer.getId(), query(null, false, null, 5));
		FeatureDtos.Page second = service.list(layer.getId(), query(null, false, first.nextCursor(), 5));

		assertThat(first.totalCount()).isEqualTo(ROWS.size());
		assertThat(second.totalCount()).as("counting again would rescan for an unchanged number")
				.isNull();
	}

	@Test
	@DisplayName("the total counts the filtered set, not the page")
	void countsTheFilteredSet() {
		FeatureDtos.Page page = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, "Straße = 'Alsterufer'", null, null, null, false, null, 2));

		assertThat(page.features()).hasSize(2);
		assertThat(page.totalCount()).isEqualTo(4);
	}

	@Test
	void filtersByExpression() {
		FeatureDtos.Page page = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, "\"Höhe\" > 40 AND Straße IS NOT NULL", null, null, null, false, null, 100));

		assertThat(page.features()).hasSize(2);
		assertThat(page.features()).allSatisfy(feature ->
				assertThat((Double) feature.properties().get("hoehe")).isGreaterThan(40));
	}

	@Test
	@DisplayName("bbox is given in 4326 and selects by the layer's own CRS")
	void filtersByBoundingBox() {
		// The first three rows sit around 550000..550250 in EPSG:25832. Expressed in
		// 4326, that is roughly 9.98 E / 53.54 N.
		FeatureDtos.Page all = service.list(layer.getId(), query(null, false, null, 100));
		FeatureDtos.Page inBox = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, null, null, new double[] { 9.0, 53.0, 11.0, 54.0 }, null, false, null, 100));

		assertThat(all.features()).hasSize(ROWS.size());
		assertThat(inBox.features())
				.as("the whole fixture lies inside this box")
				.hasSize(ROWS.size());

		FeatureDtos.Page elsewhere = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, null, null, new double[] { 2.0, 48.0, 3.0, 49.0 }, null, false, null, 100));
		assertThat(elsewhere.features()).as("Paris holds none of it").isEmpty();
	}

	/**
	 * The bbox that used to find nothing at all.
	 *
	 * <p>The rectangle was transformed into the layer's CRS by moving its four corners, and
	 * in EPSG:25832 both -180° and +180° fold back onto the central meridian: the box came
	 * out zero metres wide, and a layer of 229.876 objects reported none of them. Reproduced
	 * here on twelve rows, where the same query has to find all twelve.
	 */
	@Test
	@DisplayName("a bbox spanning the whole world finds every row, not none")
	void filtersByAWorldSpanningBoundingBox() {
		FeatureDtos.Page page = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, null, null, new double[] { -180, -90, 180, 90 }, null, false, null, 100));

		assertThat(page.totalCount()).isEqualTo(ROWS.size());
		assertThat(page.features()).hasSize(ROWS.size());
	}

	/**
	 * And the half that keeps the first one honest: a bbox too wide for the layer's CRS must
	 * still be a filter. Answering "everything" whenever the projection gives up would pass
	 * the test above and be just as wrong -- only in the other direction.
	 */
	@Test
	@DisplayName("a half-world bbox on the wrong side of the globe finds nothing")
	void filtersByAWorldSpanningBoundingBoxOnTheOtherSide() {
		FeatureDtos.Page page = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, null, null, new double[] { -180, -90, -20, 90 }, null, false, null, 100));

		assertThat(page.totalCount()).as("the fixture sits near 9,7° east").isZero();
		assertThat(page.features()).isEmpty();
	}

	/**
	 * The rows a wide bbox drops when only its four corners are projected.
	 *
	 * <p>A rectangle in lng/lat has straight edges; its image in a projected CRS does not.
	 * In UTM32 a parallel bends away from the central meridian, so the southern edge of a
	 * bbox sits at its lowest where the meridian crosses it -- in the middle, between the
	 * corners. The box around the four transformed corners therefore has its floor some
	 * twenty kilometres too high, and everything in that strip is outside a bbox the user
	 * drew around it.
	 *
	 * <p>Four objects along the same edge make the difference visible: the ones near the
	 * corners are found either way, and the one on the central meridian is the one that used
	 * to vanish. A bbox wide enough for this is nothing unusual -- this one is a view of
	 * Europe.
	 */
	@Test
	@DisplayName("a wide bbox finds the objects along its edge, not only those near its corners")
	void filtersByAWideBoundingBoxWithoutLosingItsMiddle() {
		// Just inside the southern edge of the bbox below, spread from one corner to the
		// other. 9° east is UTM32's central meridian and the point of the whole test.
		List<Double> longitudes = List.of(0.5, 9.0, 20.0, 44.5);
		Layer edgeLayer = createPointLayer(25832, 41.05, longitudes);

		try {
			FeatureDtos.Page page = service.list(edgeLayer.getId(), new FeatureQueryService.Query(
					null, false, null, null, new double[] { 0, 41, 45, 66 }, null, false, null, 100));

			assertThat(page.features())
					.extracting(feature -> feature.properties().get("laenge"))
					.containsExactlyInAnyOrderElementsOf(longitudes);
		}
		finally {
			dropPointLayer(edgeLayer);
		}
	}

	/** One point per longitude at {@code latitude}, stored in {@code srid}, labelled by its longitude. */
	private Layer createPointLayer(int srid, double latitude, List<Double> longitudes) {
		UUID layerId = UUID.randomUUID();
		String name = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(name);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom   geometry(MultiPoint, %d) NOT NULL,
				    laenge double precision
				)
				""".formatted(table, srid)).update();
		for (Double longitude : longitudes) {
			jdbc.sql("INSERT INTO " + table + " (geom, laenge) VALUES (ST_Multi(ST_Transform("
							+ "ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), :srid)), :lng)")
					.param("lng", longitude)
					.param("lat", latitude)
					.param("srid", srid)
					.update();
		}

		Layer edgeLayer = layerRepository.saveAndFlush(
				new Layer(layerId, project, "Kante", name, "MULTIPOINT", srid));
		fieldRepository.saveAndFlush(new LayerField(edgeLayer, "Länge", "laenge", "double precision", 0));
		return edgeLayer;
	}

	private void dropPointLayer(Layer edgeLayer) {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(edgeLayer.getTableName())).update();
		layerRepository.deleteById(edgeLayer.getId());
	}

	@Test
	@DisplayName("the exact selection modes survive a bbox spanning the whole world too")
	void selectsByModeAcrossAWorldSpanningBoundingBox() {
		double[] world = { -180, -90, 180, 90 };

		FeatureDtos.Page intersecting = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, null, null, world, "intersects", false, null, 100));
		FeatureDtos.Page contained = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, null, null, world, "contains", false, null, 100));

		assertThat(intersecting.features()).hasSize(ROWS.size());
		assertThat(contained.features()).as("everything lies inside the whole world").hasSize(ROWS.size());
	}

	@Test
	void returnsGeometryOnlyWhenAsked() {
		FeatureDtos.Feature without = service.list(layer.getId(), query(null, false, null, 1))
				.features().get(0);
		FeatureDtos.Feature with = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, null, null, null, null, true, null, 1)).features().get(0);

		assertThat(without.geometry()).isNull();
		assertThat(with.geometry())
				.as("full precision GeoJSON in 4326, the snapping source of plan D.1")
				.contains("\"type\":\"MultiPolygon\"")
				.contains("coordinates");
	}

	@Test
	void readsASingleFeatureWithItsRowVersion() {
		long fid = service.list(layer.getId(), query(null, false, null, 1)).features().get(0).fid();

		FeatureDtos.Feature feature = service.get(layer.getId(), fid);

		assertThat(feature.fid()).isEqualTo(fid);
		assertThat(feature.properties()).containsKeys("strasse", "hoehe");
		assertThat(feature.geometry()).isNotNull();
		assertThat(feature.rowVersion())
				.as("xmin travels from the MVP so optimistic locking needs no migration later")
				.isNotBlank();
	}

	@Test
	void reportsAnUnknownFeature() {
		assertThatThrownBy(() -> service.get(layer.getId(), 999_999))
				.isInstanceOf(NotFoundException.class);
	}

	/**
	 * The wording is part of the contract with the client, not just a nicety: the
	 * attribute table matches on it to tell this apart from a bad filter expression
	 * ("Unbekanntes Feld"), which is a 400 as well but must be left alone. Sorting is
	 * local state the table resets on its own; a filter is what the user typed.
	 *
	 * <p>So if this assertion ever fails, adjusting it is not enough --
	 * {@code frontend/src/table/sortValidity.ts} has to move with it, or the table stops
	 * recovering from a sort field that was deleted and sits on a 400 the user cannot
	 * clear from where they are.
	 */
	@Test
	void rejectsAnUnknownSortField() {
		assertThatThrownBy(() -> service.list(layer.getId(), query("gibtsnicht", false, null, 10)))
				.isInstanceOf(BadRequestException.class)
				.as("frontend/src/table/sortValidity.ts matches on this wording -- change both or neither")
				.hasMessageContaining("Unbekanntes Sortierfeld");
	}

	@Test
	void rejectsATamperedCursor() {
		assertThatThrownBy(() -> service.list(layer.getId(), query(null, false, "nicht-base64!!", 10)))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("Cursor");
	}

	@Test
	@DisplayName("without mode, only the && prefilter applies -- today's behaviour is unchanged")
	void selectsByBoundingBoxAloneWithoutAMode() {
		FeatureDtos.Page page = service.list(modeLayer.getId(), modeQuery(null));

		assertThat(labelsOf(page))
				.as("all three rows pass the bbox-only prefilter, including the one whose "
						+ "geometry never actually touches the rectangle")
				.containsExactlyInAnyOrder("innen", "kante", "nur-bbox");
	}

	@Test
	@DisplayName("intersects adds the exact test, dropping the row that only overlaps by envelope")
	void selectsByIntersection() {
		FeatureDtos.Page page = service.list(modeLayer.getId(), modeQuery("intersects"));

		assertThat(labelsOf(page)).containsExactlyInAnyOrder("innen", "kante");
	}

	@Test
	@DisplayName("contains only keeps the row that lies fully inside the rectangle")
	void selectsByContainment() {
		FeatureDtos.Page page = service.list(modeLayer.getId(), modeQuery("contains"));

		assertThat(labelsOf(page)).containsExactly("innen");
	}

	@Test
	@DisplayName("totalCount reflects the exact test too, not just the && prefilter")
	void reportsTheTotalForASelectionMode() {
		FeatureDtos.Page page = service.list(modeLayer.getId(), modeQuery("intersects"));

		assertThat(page.totalCount()).isEqualTo(2);
	}

	@Test
	void rejectsAnUnknownSelectionMode() {
		assertThatThrownBy(() -> service.list(modeLayer.getId(), modeQuery("touches")))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("Auswahlmodus");
	}

	/**
	 * A page size past the ceiling used to be clamped to it. The request came back looking
	 * complete: 1.000 rows, no cursor if the filter matched no more, and nothing anywhere
	 * saying that 4.000 rows had been left out. A person might have stopped at the round
	 * number; a program has no reason to.
	 */
	@Test
	@DisplayName("a page size past the ceiling is refused, with the ceiling in the message")
	void rejectsAPageSizeOverTheCeiling() {
		assertThatThrownBy(() -> service.list(layer.getId(), query(null, false, null, 5_000)))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("1000");
	}

	@Test
	void rejectsAPageSizeBelowOne() {
		assertThatThrownBy(() -> service.list(layer.getId(), query(null, false, null, 0)))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("size");
	}

	@Test
	@DisplayName("the ceiling itself is served in full")
	void servesThePageSizeAtTheCeiling() {
		FeatureDtos.Page page = service.list(hugeLayer.getId(), query(null, false, null, 1_000));

		assertThat(page.features()).hasSize(1_000);
	}

	// --- column types the cursor and the filter have to survive ---------------------------

	@Test
	@DisplayName("paging by a time column delivers every row once instead of failing")
	void pagesByATimeColumn() {
		assertThat(pageThroughTypeLayer("Zeit", "zeit"))
				.hasSize(3)
				.doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("paging by a uuid column delivers every row once instead of failing")
	void pagesByAUuidColumn() {
		assertThat(pageThroughTypeLayer("Kennung", "kennung"))
				.hasSize(3)
				.doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("paging by a bytea column delivers every row once instead of failing")
	void pagesByAByteaColumn() {
		// Compared as hex, because two byte arrays holding the same bytes are still two
		// different objects and neither hasSize nor doesNotHaveDuplicates would notice.
		List<String> pages = pageThroughTypeLayer("Rohdaten", "rohdaten").stream()
				.map(value -> HexFormat.of().formatHex((byte[]) value))
				.toList();

		assertThat(pages).containsExactly("01", "02", "03");
	}

	/**
	 * The one that fails without saying so. A cursor holding the middle value comes back
	 * rounded up onto the largest one, so "everything after that" excludes the largest --
	 * and the fid tie-breaker, which would otherwise catch it, is looking for a larger fid
	 * than the row it belongs to has. The walk ends one row early and reports two objects
	 * where the layer holds three.
	 */
	@Test
	@DisplayName("paging by a bigint past 2^53 keeps every digit of the cursor")
	void pagesByABigintColumn() {
		assertThat(pageThroughTypeLayer("Großzahl", "grosszahl"))
				.containsExactlyElementsOf(HUGE_NUMBERS.reversed());
	}

	/**
	 * The same failure as the bigint one, from the other end: {@code numeric} is exact and a
	 * JSON number is not, so a cursor rounded to a double no longer names any row in the
	 * table. Here the rounded value sits below all three, and the keyset's tie-breaker looks
	 * for a *larger* fid -- while the rows still to come were inserted earlier and carry
	 * smaller ones. The walk ends after the first row and quietly reports the other two as
	 * not existing.
	 */
	@Test
	@DisplayName("paging by a numeric keeps every digit of the cursor")
	void pagesByANumericColumn() {
		// Compared with compareTo, not equals: 1.10 and 1.1 are the same number and two
		// different BigDecimals, and the scale a column reports back is its own business.
		assertThat(pageThroughTypeLayer("Messwert", "messwert"))
				.map(BigDecimal.class::cast)
				.usingElementComparator(BigDecimal::compareTo)
				.containsExactlyElementsOf(EXACT_NUMBERS.reversed());
	}

	@Test
	@DisplayName("a filter compares against a time column instead of failing on a missing cast")
	void filtersByATimeValue() {
		FeatureDtos.Page page = service.list(typeLayer.getId(), typeQuery("Zeit >= '12:00:00'"));

		assertThat(page.features()).hasSize(2);
	}

	@Test
	@DisplayName("a filter compares against a uuid column instead of failing on a missing cast")
	void filtersByAUuidValue() {
		FeatureDtos.Page page = service.list(typeLayer.getId(),
				typeQuery("Kennung = '00000000-0000-0000-0000-000000000002'"));

		assertThat(page.features()).hasSize(1);
	}

	@Test
	@DisplayName("a filter compares against a bytea column instead of failing on a missing cast")
	void filtersByAByteaValue() {
		FeatureDtos.Page page = service.list(typeLayer.getId(), typeQuery("Rohdaten = '\\x02'"));

		assertThat(page.features()).hasSize(1);
	}

	// --- search ------------------------------------------------------------------------

	@Test
	@DisplayName("search matches across multiple text fields, case-insensitively, on a partial value")
	void searchFindsAcrossMultipleTextFields() {
		FeatureDtos.Page page = service.list(searchLayer.getId(), searchQuery(null, "schmidt"));

		assertThat(namesOf(page))
				.as("found through name (\"Schmidt\") and through ort (\"SCHMIDTplatz\")")
				.containsExactlyInAnyOrder("Schmidt", "Müller");
	}

	@Test
	@DisplayName("a literal % in the search term matches only that literal value")
	void searchEscapesPercentAsALiteral() {
		FeatureDtos.Page page = service.list(searchLayer.getId(), searchQuery(null, "50%"));

		assertThat(namesOf(page))
				.as("\"50\" alone must not match a pattern search=50% would produce unescaped")
				.containsExactly("50%");
	}

	@Test
	@DisplayName("a literal _ in the search term does not act as a single-character wildcard")
	void searchEscapesUnderscoreAsALiteral() {
		FeatureDtos.Page page = service.list(searchLayer.getId(), searchQuery(null, "5_"));

		assertThat(namesOf(page))
				.as("\"50\" would match an unescaped 5_ pattern; \"5_x\" only matches the escaped one")
				.containsExactly("5_x");
	}

	@Test
	@DisplayName("a value that only appears in a numeric column is not found -- numeric fields are not searched")
	void searchIgnoresNonTextFields() {
		FeatureDtos.Page page = service.list(searchLayer.getId(), searchQuery(null, "777"));

		assertThat(page.features())
				.as("777 sits only in the nummer column of one row, in no text field of any row")
				.isEmpty();
	}

	@Test
	@DisplayName("a search term with an apostrophe matches literally, proving it is a bind parameter")
	void searchMatchesALiteralApostrophe() {
		FeatureDtos.Page page = service.list(searchLayer.getId(), searchQuery(null, "O'Brien"));

		assertThat(namesOf(page)).containsExactly("O'Brien");
	}

	@Test
	@DisplayName("a literal backslash in the search term matches without a dangling escape character")
	void searchMatchesALiteralBackslash() {
		FeatureDtos.Page page = service.list(searchLayer.getId(), searchQuery(null, "C:\\Windows"));

		assertThat(namesOf(page)).containsExactly("C:\\Windows");
	}

	@Test
	@DisplayName("quotes, wildcards and SQL-like content in search never reach the database as SQL")
	void searchTreatsInjectionAttemptsAsPureData() {
		String payload = "'; DROP TABLE " + searchTableName + "; --";

		FeatureDtos.Page page = service.list(searchLayer.getId(), searchQuery(null, payload));

		assertThat(page.features()).as("no row contains this literal string").isEmpty();
		Long stillThere = jdbc
				.sql("SELECT COUNT(*) FROM " + SqlIdentifier.quoteLayerTable(searchTableName))
				.query(Long.class)
				.single();
		assertThat(stillThere).as("the payload never left the bind parameter").isGreaterThan(0);
	}

	@Test
	@DisplayName("a blank search behaves as if it were not given")
	void blankSearchIsTreatedAsAbsent() {
		FeatureDtos.Page withBlank = service.list(searchLayer.getId(), searchQuery(null, "   "));
		FeatureDtos.Page withoutSearch = service.list(searchLayer.getId(), searchQuery(null, null));

		assertThat(withBlank.totalCount()).isEqualTo(withoutSearch.totalCount());
	}

	@Test
	@DisplayName("search on a layer without a single text field is rejected, not silently empty")
	void searchOnLayerWithoutTextFieldsIsRejected() {
		assertThatThrownBy(() -> service.list(noTextLayer.getId(), searchQuery(null, "irgendwas")))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("keine Textfelder");
	}

	@Test
	@DisplayName("filter and search combine with AND, not OR")
	void filterAndSearchCombineWithAnd() {
		FeatureDtos.Page page = service.list(searchLayer.getId(), searchQuery("Nummer = 50", "Schmidt"));

		assertThat(namesOf(page))
				.as("the nummer-only match (name/ort both null) and the search-only match "
						+ "(nummer 99) must both be excluded")
				.containsExactly("Schmidt");
	}

	// --- one name, two fields ------------------------------------------------------------

	/**
	 * The two ways into the same word have to agree. Before, they did not: the filter took
	 * the field whose display name matched and the sort parameter the first field in ordinal
	 * order, so "kronendurchmesser" meant the text column to one and the bigint column to
	 * the other. Both now refuse it, which is the only answer that cannot be mistaken for a
	 * result.
	 */
	@Test
	@DisplayName("filter and sort answer an ambiguous name the same way")
	void refusesAnAmbiguousNameOnBothPaths() {
		assertThatThrownBy(() -> service.list(collidingLayer.getId(), new FeatureQueryService.Query(
				null, false, "kronendurchmesser > 10", null, null, null, false, null, 100)))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("Mehrdeutiges Feld: kronendurchmesser");

		assertThatThrownBy(() -> service.list(collidingLayer.getId(),
				query("kronendurchmesser", false, null, 100)))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("Mehrdeutiges Sortierfeld: kronendurchmesser");
	}

	@Test
	@DisplayName("the message names both candidates and how to reach each")
	void namesBothCandidatesOfAnAmbiguousName() {
		assertThatThrownBy(() -> service.list(collidingLayer.getId(), new FeatureQueryService.Query(
				null, false, "kronendurchmesser > 10", null, null, null, false, null, 100)))
				.hasMessageContaining("Kronendurchmesser Quelle (Spalte kronendurchmesser, Id "
						+ bigintFieldId + ")")
				.hasMessageContaining("Kronendurchmesser (Spalte kronendurchmesser_z, Id "
						+ textFieldId + ")")
				.hasMessageContaining("Eindeutig sind: Kronendurchmesser Quelle, kronendurchmesser_z");
	}

	/**
	 * And the point of refusing: the two unambiguous names really do read different columns,
	 * one as a number and one as text. Guessing between them was never a small difference.
	 */
	@Test
	@DisplayName("the two unambiguous names count different rows")
	void theTwoFieldsBehindAnAmbiguousNameAreNotTheSame() {
		FeatureDtos.Page byNumber = service.list(collidingLayer.getId(), new FeatureQueryService.Query(
				null, false, "\"Kronendurchmesser Quelle\" > 10", null, null, null, false, null, 100));
		FeatureDtos.Page byText = service.list(collidingLayer.getId(), new FeatureQueryService.Query(
				null, false, "kronendurchmesser_z > '10'", null, null, null, false, null, 100));

		assertThat(byNumber.totalCount()).as("only 12 is greater than 10").isEqualTo(1);
		assertThat(byText.totalCount())
				.as("as text, 2, 3 and 9 sort after '10' as well -- the count is four times the other")
				.isEqualTo(4);
	}

	/**
	 * The id is what the ambiguity message offers, so it has to be reachable from both
	 * paths and it has to hit the field it names -- not merely "a" field.
	 */
	@Test
	@DisplayName("the field id filters, and reaches the field the name could not")
	void filtersByFieldId() {
		FeatureDtos.Page byNumberField = service.list(collidingLayer.getId(),
				new FeatureQueryService.Query(null, false, bigintFieldId + " > 10", null, null,
						null, false, null, 100));
		FeatureDtos.Page byTextField = service.list(collidingLayer.getId(),
				new FeatureQueryService.Query(null, false, textFieldId + " > '10'", null, null,
						null, false, null, 100));

		assertThat(byNumberField.totalCount()).as("the bigint field, compared as a number").isEqualTo(1);
		assertThat(byTextField.totalCount()).as("the text field, compared as text").isEqualTo(4);
	}

	@Test
	@DisplayName("the field id sorts")
	void sortsByFieldId() {
		FeatureDtos.Page page = service.list(collidingLayer.getId(),
				query(bigintFieldId.toString(), true, null, 100));

		assertThat(page.features().stream()
				.map(feature -> feature.properties().get("kronendurchmesser")))
				.containsExactly(12L, 9L, 3L, 2L);
	}

	@Test
	@DisplayName("the ambiguity message hands out the id of every candidate")
	void namesTheFieldIdsOfAnAmbiguousName() {
		assertThatThrownBy(() -> service.list(collidingLayer.getId(), new FeatureQueryService.Query(
				null, false, "kronendurchmesser > 10", null, null, null, false, null, 100)))
				.hasMessageContaining("Id " + bigintFieldId)
				.hasMessageContaining("Id " + textFieldId)
				.hasMessageContaining("Die Id trifft immer genau ein Feld");
	}

	@Test
	@DisplayName("an unambiguous name sorts as before")
	void sortsByAnUnambiguousName() {
		FeatureDtos.Page page = service.list(collidingLayer.getId(),
				query("Kronendurchmesser Quelle", false, null, 100));

		assertThat(page.features().stream()
				.map(feature -> feature.properties().get("kronendurchmesser")))
				.containsExactly(2L, 3L, 9L, 12L);
	}

	// --- a text column is not ordered against a number -------------------------------------

	/**
	 * The same fixture carries the other half of the story. {@code kronendurchmesser_z} is
	 * the text twin of a bigint column, and ordering it against a number is the quiet wrong
	 * answer: on the real layer {@code > 10} counted 225.657 of 229.876 rows where 73.890 is
	 * the honest number.
	 */
	@Test
	@DisplayName("ordering a text column against a number is refused")
	void refusesToOrderATextColumnAgainstANumber() {
		assertThatThrownBy(() -> service.list(collidingLayer.getId(), new FeatureQueryService.Query(
				null, false, "kronendurchmesser_z > 10", null, null, null, false, null, 100)))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("ist vom Typ text");
	}

	@Test
	@DisplayName("the message names the numeric field of the layer, with its id")
	void namesTheNumericFieldIdWhenRefusingATextComparison() {
		assertThatThrownBy(() -> service.list(collidingLayer.getId(), new FeatureQueryService.Query(
				null, false, "kronendurchmesser_z > 10", null, null, null, false, null, 100)))
				.hasMessageContaining("Kronendurchmesser Quelle (Id " + bigintFieldId + ")");
	}

	/** Quoted, the same comparison is a text comparison and is still served. */
	@Test
	@DisplayName("the quoted form still runs, and still counts four")
	void servesTheQuotedTextComparison() {
		FeatureDtos.Page page = service.list(collidingLayer.getId(), new FeatureQueryService.Query(
				null, false, "kronendurchmesser_z > '10'", null, null, null, false, null, 100));

		assertThat(page.totalCount()).isEqualTo(4);
	}

	@Test
	@DisplayName("sorting by that column is untouched, and stays lexical")
	void sortsATextColumnLexicallyAsBefore() {
		FeatureDtos.Page page = service.list(collidingLayer.getId(),
				query("kronendurchmesser_z", true, null, 100));

		assertThat(page.features().stream()
				.map(feature -> feature.properties().get("kronendurchmesser_z")))
				.as("'9' before '3' before '2' before '12' -- character by character, on purpose")
				.containsExactly("9", "3", "2", "12");
	}

	// --- fid as a filterable field --------------------------------------------------------

	@Test
	@DisplayName("fid compares like a number column")
	void filtersByFid() {
		List<Long> all = service.fids(layer.getId(), null, null).fids();
		long third = all.get(2);

		FeatureDtos.Page page = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, "fid > " + third, null, null, null, false, null, 100));

		assertThat(page.totalCount()).isEqualTo(ROWS.size() - 3);
		assertThat(page.features()).allSatisfy(feature -> assertThat(feature.fid()).isGreaterThan(third));
	}

	@Test
	@DisplayName("fid IN names an exact set of objects")
	void filtersByAnExplicitFidList() {
		List<Long> all = service.fids(layer.getId(), null, null).fids();
		List<Long> wanted = List.of(all.get(0), all.get(4), all.get(9));

		FeatureDtos.Page page = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, "fid IN (" + join(wanted) + ")", null, null, null, false, null, 100));

		assertThat(page.features().stream().map(FeatureDtos.Feature::fid))
				.containsExactlyInAnyOrderElementsOf(wanted);
	}

	@Test
	@DisplayName("fid NOT IN is the complement of the same list")
	void filtersByANegatedFidList() {
		List<Long> all = service.fids(layer.getId(), null, null).fids();
		List<Long> excluded = List.of(all.get(0), all.get(1));

		FeatureDtos.Page page = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, "fid NOT IN (" + join(excluded) + ")", null, null, null, false, null, 100));

		assertThat(page.totalCount()).isEqualTo(ROWS.size() - excluded.size());
		assertThat(page.features().stream().map(FeatureDtos.Feature::fid)).doesNotContainAnyElementsOf(excluded);
	}

	/**
	 * The reason the list is bound as one array rather than one placeholder per value:
	 * expanded, this expression would be 70.000 bind parameters and PostgreSQL refuses past
	 * 65535. Deliberately past that number, not merely large -- a selection here runs to
	 * 100.000 objects, so a program re-reading one arrives on the far side of the ceiling.
	 */
	@Test
	@DisplayName("a fid list past the bind-parameter ceiling still runs")
	void filtersByAVeryLongFidList() {
		List<Long> wanted = service.fids(hugeLayer.getId(), "Bucket = 2", null).fids()
				.subList(0, 70_000);

		FeatureDtos.Page page = service.list(hugeLayer.getId(), new FeatureQueryService.Query(
				null, false, "fid IN (" + join(wanted) + ")", null, null, null, false, null, 1_000));

		assertThat(page.totalCount()).isEqualTo(wanted.size());
		assertThat(page.features()).hasSize(1_000);
	}

	@Test
	@DisplayName("fid filters and fid sorting name the same column")
	void filtersAndSortsByFidTogether() {
		List<Long> all = service.fids(layer.getId(), null, null).fids();

		FeatureDtos.Page page = service.list(layer.getId(), new FeatureQueryService.Query(
				"fid", true, "fid <= " + all.get(2), null, null, null, false, null, 100));

		assertThat(page.features().stream().map(FeatureDtos.Feature::fid))
				.containsExactly(all.get(2), all.get(1), all.get(0));
	}

	private static String join(List<Long> fids) {
		return fids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
	}

	// --- fid endpoint --------------------------------------------------------------------

	@Test
	@DisplayName("without filter or search, the fid endpoint returns every fid of the layer")
	void fidsWithoutARestrictionReturnsEveryFid() {
		FeatureDtos.FidsResponse response = service.fids(layer.getId(), null, null);

		assertThat(response.totalCount()).isEqualTo(ROWS.size());
		assertThat(response.fids()).hasSize(ROWS.size());
		assertThat(response.fids()).as("stable, ascending order").isSorted();
	}

	@Test
	@DisplayName("the fid endpoint applies filter and search exactly like list does")
	void fidsAppliesFilterAndSearch() {
		FeatureDtos.FidsResponse response = service.fids(searchLayer.getId(), "Nummer = 50", "Schmidt");

		assertThat(response.totalCount()).isEqualTo(1);
		assertThat(response.fids()).hasSize(1);
	}

	@Test
	@DisplayName("the fid endpoint returns every match, including far past a single page's worth")
	void fidsReturnsEveryMatchBeyondAPageBoundary() {
		FeatureDtos.FidsResponse response = service.fids(hugeLayer.getId(), "Bucket = 1", null);

		assertThat(response.totalCount()).isEqualTo(HUGE_BUCKET_SIZE);
		assertThat(response.fids())
				.as("well past the 1.000-row page size list() would have capped this at")
				.hasSize(HUGE_BUCKET_SIZE);
	}

	@Test
	@DisplayName("totalCount always equals the size of fids")
	void fidsTotalCountMatchesTheListSize() {
		FeatureDtos.FidsResponse response = service.fids(hugeLayer.getId(), "Bucket = 1", null);

		assertThat(response.totalCount()).isEqualTo(response.fids().size());
	}

	@Test
	@DisplayName("a restriction matching more than the upper bound is rejected, naming the actual count")
	void fidsRejectsARestrictionOverTheUpperBound() {
		assertThatThrownBy(() -> service.fids(hugeLayer.getId(), null, null))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining(String.valueOf(HUGE_TOTAL_SIZE));
	}

	@Test
	@DisplayName("a restriction matching nothing is an empty list with totalCount 0, not a 404")
	void fidsWithNoMatchesIsAnEmptyListNotAnError() {
		FeatureDtos.FidsResponse response =
				service.fids(layer.getId(), "Straße = 'Nichtvorhanden'", null);

		assertThat(response.fids()).isEmpty();
		assertThat(response.totalCount()).isEqualTo(0);
	}

	@Test
	void fidsRejectsAnUnknownLayer() {
		assertThatThrownBy(() -> service.fids(UUID.randomUUID(), null, null))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	@DisplayName("a malformed filter on the fid endpoint fails exactly as FilterParser reports it")
	void fidsPropagatesAMalformedFilterExpression() {
		assertThatThrownBy(() -> service.fids(layer.getId(), "Straße = ", null))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("Filter ungültig");
	}

	@Test
	@DisplayName("search on the fid endpoint rejects a layer without text fields, same as list does")
	void fidsRejectsSearchOnALayerWithoutTextFields() {
		assertThatThrownBy(() -> service.fids(noTextLayer.getId(), null, "irgendwas"))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("keine Textfelder");
	}
}
