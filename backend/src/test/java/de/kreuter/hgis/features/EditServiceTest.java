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
import de.kreuter.hgis.common.ConflictException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.dto.EditDtos;
import de.kreuter.hgis.features.dto.FeatureDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
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
 * The edit batch against a real PostGIS table.
 *
 * Every test writes, so unlike the query tests this one builds a fresh layer per method
 * -- a leftover row from a previous test would make a feature count meaningless.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EditServiceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** A square around 9.98 E / 53.54 N -- Hamburg, where the rest of the fixtures live. */
	private static final String SQUARE = """
			{"type":"Polygon","coordinates":[[[9.98,53.54],[9.99,53.54],[9.99,53.55],[9.98,53.55],[9.98,53.54]]]}
			""";

	/** Bow-tie: the classic self-intersection, invalid but perfectly drawable. */
	private static final String BOW_TIE = """
			{"type":"Polygon","coordinates":[[[9.98,53.54],[9.99,53.55],[9.99,53.54],[9.98,53.55],[9.98,53.54]]]}
			""";

	private static final String LINE = """
			{"type":"LineString","coordinates":[[9.98,53.54],[9.99,53.55]]}
			""";

	@Autowired
	private EditService editService;

	@Autowired
	private FeatureQueryService queryService;

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

	@BeforeEach
	void createLayer() {
		project = projectRepository.saveAndFlush(
				new Project("Edit-Test " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom    geometry(MultiPolygon, 25832) NOT NULL,
				    strasse text,
				    hoehe   double precision
				)
				""".formatted(SqlIdentifier.quoteLayerTable(tableName))).update();

		layer = layerRepository.saveAndFlush(
				new Layer(layerId, project, "Editierbar", tableName, "MULTIPOLYGON", 25832));
		fieldRepository.saveAndFlush(new LayerField(layer, "Straße", "strasse", "text", 0));
		fieldRepository.saveAndFlush(new LayerField(layer, "Höhe", "hoehe", "double precision", 1));
	}

	@AfterEach
	void dropLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	private static JsonNode json(String geoJson) {
		return MAPPER.readTree(geoJson);
	}

	private EditDtos.Response apply(EditDtos.Request request) {
		return editService.apply(layer.getId(), request, null);
	}

	private EditDtos.Request creating(String geometry, Map<String, Object> properties) {
		return new EditDtos.Request(
				List.of(new EditDtos.Create(-1, json(geometry), properties)), null, null, false);
	}

	private long rowCount() {
		return jdbc.sql("SELECT COUNT(*) FROM " + SqlIdentifier.quoteLayerTable(tableName))
				.query(Long.class).single();
	}

	@Test
	@DisplayName("a created feature gets a real fid and its attributes")
	void createsAFeature() {
		EditDtos.Response response = apply(creating(SQUARE, Map.of("strasse", "Neue Gasse", "hoehe", 12.5)));

		assertThat(response.createdFids()).containsOnlyKeys(-1L);
		long fid = response.createdFids().get(-1L);

		FeatureDtos.Feature feature = queryService.get(layer.getId(), fid);
		assertThat(feature.properties()).containsEntry("strasse", "Neue Gasse");
		assertThat(feature.properties()).containsEntry("hoehe", 12.5);
	}

	@Test
	@DisplayName("a drawn polygon is promoted to multi and reprojected, like an imported one")
	void storesGeometryLikeTheImportDoes() {
		long fid = apply(creating(SQUARE, Map.of())).createdFids().get(-1L);

		Map<String, Object> stored = jdbc.sql("SELECT GeometryType(geom) AS type, ST_SRID(geom) AS srid"
						+ " FROM " + SqlIdentifier.quoteLayerTable(tableName) + " WHERE fid = :fid")
				.param("fid", fid)
				.query()
				.singleRow();

		assertThat(stored.get("type")).isEqualTo("MULTIPOLYGON");
		assertThat(stored.get("srid")).isEqualTo(25832);
	}

	@Test
	@DisplayName("the layer's bookkeeping moves with the write")
	void updatesLayerStateAfterAWrite() {
		long versionBefore = layerRepository.findById(layer.getId()).orElseThrow().getDataVersion();

		EditDtos.Response response = apply(creating(SQUARE, Map.of()));

		assertThat(response.featureCount()).isEqualTo(1);
		assertThat(response.dataVersion())
				.as("the tile URL is built from this; without a bump the map keeps the old tiles")
				.isGreaterThan(versionBefore);

		Layer reloaded = layerRepository.findById(layer.getId()).orElseThrow();
		assertThat(reloaded.getFeatureCount()).isEqualTo(1);
		assertThat(reloaded.getExtent()).as("extent must follow what was drawn").isNotNull();
	}

	@Test
	@DisplayName("an invalid geometry is refused with its reason and location, and nothing is written")
	void refusesAnInvalidGeometryWithoutRepairing() {
		assertThatThrownBy(() -> apply(creating(BOW_TIE, Map.of())))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("Ungültige Geometrie")
				.hasMessageContaining("Self-intersection")
				// The coordinate is what lets the UI zoom to the problem instead of
				// leaving the user to find it.
				.hasMessageMatching("(?s).*bei 9,9\\d+, 53,5\\d+.*|(?s).*bei 9\\.9\\d+, 53\\.5\\d+.*");

		assertThat(rowCount()).isZero();
	}

	@Test
	@DisplayName("repair happens only when it was asked for")
	void repairsOnlyOnRequest() {
		EditDtos.Request repairing = new EditDtos.Request(
				List.of(new EditDtos.Create(-1, json(BOW_TIE), Map.of())), null, null, true);

		long fid = apply(repairing).createdFids().get(-1L);

		Boolean valid = jdbc.sql("SELECT ST_IsValid(geom) FROM "
						+ SqlIdentifier.quoteLayerTable(tableName) + " WHERE fid = :fid")
				.param("fid", fid)
				.query(Boolean.class)
				.single();
		assertThat(valid).isTrue();
	}

	@Test
	@DisplayName("a geometry of the wrong kind is named, not left to a constraint violation")
	void refusesAGeometryTheLayerCannotHold() {
		assertThatThrownBy(() -> apply(creating(LINE, Map.of())))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("Flächen")
				.hasMessageContaining("Linien");
	}

	@Test
	@DisplayName("a MULTIPOINT layer still refuses a polygon -- the type binding stays for the specific types")
	void aMultipointLayerStillRefusesAPolygon() {
		UUID pointLayerId = UUID.randomUUID();
		String pointTable = SqlIdentifier.tableName(pointLayerId);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(MultiPoint, 25832) NOT NULL
				)
				""".formatted(SqlIdentifier.quoteLayerTable(pointTable))).update();
		Layer pointLayer = layerRepository.saveAndFlush(
				new Layer(pointLayerId, project, "Punkte", pointTable, "MULTIPOINT", 25832));

		try {
			EditDtos.Request request = new EditDtos.Request(
					List.of(new EditDtos.Create(-1, json(SQUARE), Map.of())), null, null, false);

			assertThatThrownBy(() -> editService.apply(pointLayer.getId(), request, null))
					.isInstanceOf(BadRequestException.class)
					.hasMessageContaining("Punkte")
					.hasMessageContaining("Flächen");
		}
		finally {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(pointTable)).update();
			layerRepository.deleteById(pointLayer.getId());
		}
	}

	@Test
	@DisplayName("a GEOMETRY layer accepts a point, a line and a polygon, one after another")
	void aGeometryLayerAcceptsAPointALineAndAPolygonInSequence() {
		UUID mixedLayerId = UUID.randomUUID();
		String mixedTable = SqlIdentifier.tableName(mixedLayerId);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(Geometry, 25832) NOT NULL
				)
				""".formatted(SqlIdentifier.quoteLayerTable(mixedTable))).update();
		Layer mixedLayer = layerRepository.saveAndFlush(
				new Layer(mixedLayerId, project, "Gemischt", mixedTable, "GEOMETRY", 25832));

		try {
			String point = """
					{"type":"Point","coordinates":[9.98,53.54]}
					""";

			// Applied one batch at a time, like a user drawing three different shapes into
			// the same layer over time -- not a single batch, to prove the layer keeps
			// accepting whatever comes next rather than only tolerating a mix within one
			// request.
			editService.apply(mixedLayer.getId(), new EditDtos.Request(
					List.of(new EditDtos.Create(-1, json(point), Map.of())), null, null, false), null);
			editService.apply(mixedLayer.getId(), new EditDtos.Request(
					List.of(new EditDtos.Create(-2, json(LINE), Map.of())), null, null, false), null);
			editService.apply(mixedLayer.getId(), new EditDtos.Request(
					List.of(new EditDtos.Create(-3, json(SQUARE), Map.of())), null, null, false), null);

			List<String> storedTypes = jdbc.sql("SELECT GeometryType(geom) AS type FROM "
							+ SqlIdentifier.quoteLayerTable(mixedTable) + " ORDER BY fid")
					.query(String.class)
					.list();
			assertThat(storedTypes).containsExactly("MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON");
		}
		finally {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(mixedTable)).update();
			layerRepository.deleteById(mixedLayer.getId());
		}
	}

	@Test
	void updatesAttributesWithoutTouchingTheGeometry() {
		long fid = apply(creating(SQUARE, Map.of("strasse", "Alt"))).createdFids().get(-1L);
		String geometryBefore = queryService.get(layer.getId(), fid).geometry();

		apply(new EditDtos.Request(null,
				List.of(new EditDtos.Update(fid, null, null, Map.of("strasse", "Neu"))), null, false));

		FeatureDtos.Feature after = queryService.get(layer.getId(), fid);
		assertThat(after.properties()).containsEntry("strasse", "Neu");
		assertThat(after.geometry()).isEqualTo(geometryBefore);
	}

	@Test
	@DisplayName("a stale row version is a conflict, and the current state comes back with it")
	void rejectsAStaleRowVersion() {
		long fid = apply(creating(SQUARE, Map.of("strasse", "Erst"))).createdFids().get(-1L);
		String staleVersion = "1";

		assertThatThrownBy(() -> apply(new EditDtos.Request(null,
				List.of(new EditDtos.Update(fid, staleVersion, null, Map.of("strasse", "Zweit"))),
				null, false)))
				.isInstanceOf(ConflictException.class)
				.satisfies(thrown -> assertThat(((ConflictException) thrown).getCurrent())
						.as("the UI has to be able to show what it would overwrite")
						.containsKey("row_version"));

		assertThat(queryService.get(layer.getId(), fid).properties())
				.as("a refused update must not have written anything")
				.containsEntry("strasse", "Erst");
	}

	@Test
	void acceptsTheCurrentRowVersion() {
		long fid = apply(creating(SQUARE, Map.of("strasse", "Erst"))).createdFids().get(-1L);
		String current = queryService.get(layer.getId(), fid).rowVersion();

		apply(new EditDtos.Request(null,
				List.of(new EditDtos.Update(fid, current, null, Map.of("strasse", "Zweit"))), null, false));

		assertThat(queryService.get(layer.getId(), fid).properties()).containsEntry("strasse", "Zweit");
	}

	@Test
	void reportsAnUpdateToAFeatureThatIsGone() {
		assertThatThrownBy(() -> apply(new EditDtos.Request(null,
				List.of(new EditDtos.Update(999_999, null, null, Map.of("strasse", "X"))), null, false)))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	void deletesFeatures() {
		long first = apply(creating(SQUARE, Map.of())).createdFids().get(-1L);
		long second = apply(creating(SQUARE, Map.of())).createdFids().get(-1L);

		EditDtos.Response response = apply(new EditDtos.Request(null, null, List.of(first, second), false));

		assertThat(response.deleted()).isEqualTo(2);
		assertThat(rowCount()).isZero();
	}

	@Test
	@DisplayName("one failure rolls back the whole batch")
	void rollsBackTheWholeBatchOnOneFailure() {
		// The point of sending edits together: a batch that fails halfway would otherwise
		// leave the client unable to say which of its changes are on the server.
		EditDtos.Request mixed = new EditDtos.Request(
				List.of(new EditDtos.Create(-1, json(SQUARE), Map.of()),
						new EditDtos.Create(-2, json(BOW_TIE), Map.of())),
				null, null, false);

		assertThatThrownBy(() -> apply(mixed)).isInstanceOf(BadRequestException.class);

		assertThat(rowCount())
				.as("the valid feature of the same batch must not survive")
				.isZero();
	}

	@Test
	void rejectsAnUnknownProperty() {
		assertThatThrownBy(() -> apply(creating(SQUARE, Map.of("passwort", "geheim"))))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("Unbekanntes Feld: passwort");
	}

	/**
	 * Befund 2 (Validierung, 27.08.), point 3: a property key is resolved by {@link
	 * de.kreuter.hgis.catalog.LayerFields#require} now, the same case-insensitive, three
	 * spellings rule the sort parameter and filter expressions already use -- not by an
	 * exact match against the lower-cased column name a plain map lookup used to require.
	 * The fixture's field is created as {@code LayerField(layer, "Straße", "strasse", ...)}
	 * on purpose: display name and column name differ both in case and in spelling
	 * ("ß" vs "ss"), so a fix that only lower-cased the key would still miss this.
	 */
	@Test
	@DisplayName("a property key matches its field by column name regardless of case")
	void createsAFeatureWithAPropertyKeyMatchingTheColumnNameInAnyCase() {
		EditDtos.Response response = apply(creating(SQUARE, Map.of("STRASSE", "Obere Gasse")));

		long fid = response.createdFids().get(-1L);
		assertThat(queryService.get(layer.getId(), fid).properties()).containsEntry("strasse", "Obere Gasse");
	}

	/**
	 * The exact round trip Befund 2 reports: a caller that reads {@code describe_layer}'s
	 * {@code name} for an existing field -- here "Straße", not its column "strasse" -- and
	 * writes a new feature back using that same spelling must not be rejected for it.
	 */
	@Test
	@DisplayName("a property key matches its field by its display name, umlaut and all")
	void createsAFeatureWithAPropertyKeyMatchingTheDisplayName() {
		EditDtos.Response response = apply(creating(SQUARE, Map.of("Straße", "Untere Gasse")));

		long fid = response.createdFids().get(-1L);
		assertThat(queryService.get(layer.getId(), fid).properties()).containsEntry("strasse", "Untere Gasse");
	}

	@Test
	void rejectsAnEmptyBatch() {
		assertThatThrownBy(() -> apply(new EditDtos.Request(null, null, null, false)))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("leer");
	}

	@Test
	void rejectsMalformedGeoJson() {
		assertThatThrownBy(() -> apply(creating("{\"type\":\"Nonsense\",\"coordinates\":[1,2]}", Map.of())))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("Geometrie");
	}

	@Test
	@DisplayName("creates, updates and deletes in one batch all take effect")
	void appliesAllThreeKindsTogether() {
		long toUpdate = apply(creating(SQUARE, Map.of("strasse", "Alt"))).createdFids().get(-1L);
		long toDelete = apply(creating(SQUARE, Map.of())).createdFids().get(-1L);

		EditDtos.Response response = apply(new EditDtos.Request(
				List.of(new EditDtos.Create(-7, json(SQUARE), Map.of("strasse", "Ganz neu"))),
				List.of(new EditDtos.Update(toUpdate, null, json(SQUARE), Map.of("strasse", "Geändert"))),
				List.of(toDelete),
				false));

		assertThat(response.createdFids()).containsKey(-7L);
		assertThat(response.updated()).isEqualTo(1);
		assertThat(response.deleted()).isEqualTo(1);
		assertThat(response.featureCount()).isEqualTo(2);
		assertThat(queryService.get(layer.getId(), toUpdate).properties())
				.containsEntry("strasse", "Geändert");
	}

	@Test
	@DisplayName("a batch updates several features at once, each across several typed columns, nulls included")
	void updatesSeveralFeaturesAcrossSeveralTypesInOneBatch() {
		// This is the shape a spreadsheet-style cell edit produces in bulk: many rows,
		// several columns of different types each, and clearing a cell means sending
		// null for it -- unlike the rest of this file, which edits one feature and one
		// or two columns at a time. A dedicated table with more column types than the
		// shared fixture is what makes that worth testing here.
		UUID richLayerId = UUID.randomUUID();
		String richTable = SqlIdentifier.tableName(richLayerId);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom   geometry(MultiPolygon, 25832) NOT NULL,
				    txt    text,
				    cnt    integer,
				    amt    numeric(10,2),
				    active boolean,
				    whn    date,
				    seen   timestamptz,
				    ident  uuid,
				    blob   bytea
				)
				""".formatted(SqlIdentifier.quoteLayerTable(richTable))).update();

		Layer richLayer = layerRepository.saveAndFlush(
				new Layer(richLayerId, project, "Vieltypig", richTable, "MULTIPOLYGON", 25832));
		fieldRepository.saveAndFlush(new LayerField(richLayer, "Text", "txt", "text", 0));
		fieldRepository.saveAndFlush(new LayerField(richLayer, "Anzahl", "cnt", "integer", 1));
		fieldRepository.saveAndFlush(new LayerField(richLayer, "Betrag", "amt", "numeric", 2));
		fieldRepository.saveAndFlush(new LayerField(richLayer, "Aktiv", "active", "boolean", 3));
		fieldRepository.saveAndFlush(new LayerField(richLayer, "Datum", "whn", "date", 4));
		fieldRepository.saveAndFlush(new LayerField(richLayer, "Gesehen", "seen", "timestamptz", 5));
		fieldRepository.saveAndFlush(new LayerField(richLayer, "Kennung", "ident", "uuid", 6));
		fieldRepository.saveAndFlush(new LayerField(richLayer, "Anhang", "blob", "bytea", 7));

		try {
			long first = insertRichRow(richTable);
			long second = insertRichRow(richTable);

			UUID newIdent = UUID.randomUUID();
			String newBlob = Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 });

			Map<String, Object> firstUpdate = Map.of(
					"txt", "Neu 1",
					"cnt", 7,
					"amt", 42.5,
					"active", true,
					"whn", "2024-06-15",
					"seen", "2024-06-15T10:30:00.000Z",
					"ident", newIdent.toString(),
					"blob", newBlob);

			// Clearing every column of a row: each key present with an explicit null,
			// exactly what the UI sends for an emptied cell.
			Map<String, Object> secondUpdate = new LinkedHashMap<>();
			for (String column : List.of("txt", "cnt", "amt", "active", "whn", "seen", "ident", "blob")) {
				secondUpdate.put(column, null);
			}

			EditDtos.Response response = editService.apply(richLayer.getId(), new EditDtos.Request(null,
					List.of(new EditDtos.Update(first, null, null, firstUpdate),
							new EditDtos.Update(second, null, null, secondUpdate)),
					null, false), null);

			assertThat(response.updated()).isEqualTo(2);

			Map<String, Object> firstProps = queryService.get(richLayer.getId(), first).properties();
			assertThat(firstProps.get("txt")).isEqualTo("Neu 1");
			assertThat(firstProps.get("cnt")).isEqualTo(7);
			assertThat((BigDecimal) firstProps.get("amt")).isEqualByComparingTo("42.50");
			assertThat(firstProps.get("active")).isEqualTo(true);
			assertThat(firstProps.get("whn")).isEqualTo(LocalDate.of(2024, 6, 15));
			assertThat(((java.util.Date) firstProps.get("seen")).toInstant())
					.isEqualTo(Instant.parse("2024-06-15T10:30:00.000Z"));
			assertThat(firstProps.get("ident")).isEqualTo(newIdent);
			assertThat((byte[]) firstProps.get("blob")).isEqualTo(new byte[] { 1, 2, 3 });

			Map<String, Object> secondProps = queryService.get(richLayer.getId(), second).properties();
			for (String column : List.of("txt", "cnt", "amt", "active", "whn", "seen", "ident", "blob")) {
				assertThat(secondProps.get(column)).as("column %s", column).isNull();
			}
		}
		finally {
			jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(richTable)).update();
			layerRepository.deleteById(richLayer.getId());
		}
	}

	private long insertRichRow(String table) {
		return jdbc.sql("INSERT INTO " + SqlIdentifier.quoteLayerTable(table)
						+ " (geom, txt, cnt, amt, active, whn, seen, ident, blob) VALUES ("
						+ "ST_Multi(ST_GeomFromText('POLYGON((0 0,1 0,1 1,0 1,0 0))', 25832)), "
						+ "'Alt', 1, 1.00, false, '2024-01-01', '2024-01-01T00:00:00Z', "
						+ "gen_random_uuid(), '\\x010203') RETURNING fid")
				.query(Long.class).single();
	}
}
