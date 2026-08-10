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
		return editService.apply(layer.getId(), request);
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
}
