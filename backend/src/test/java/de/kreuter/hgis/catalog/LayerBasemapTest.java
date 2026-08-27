package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.basemap.BasemapCatalog;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A layer's own basemap and basemap opacity (CONTRACT.md phase 18): {@code null} means
 * "follow the project's basemap", a state distinct from any concrete value, so setting
 * and resetting both have to be observable through the same PATCH endpoint that already
 * carries the rest of a layer's settings.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LayerBasemapTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	private Project project;
	private Layer layer;
	private String tableName;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Karten-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(table)).update();

		Layer newLayer = new Layer(layerId, project, "Gebäude", tableName, "MULTIPOLYGON", 25832);
		layer = layerRepository.saveAndFlush(newLayer);
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	@Test
	void anExistingLayerWithoutTheFieldsIsReadAsFollowingTheProject() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").doesNotExist())
				.andExpect(jsonPath("$.basemapOpacity").doesNotExist());

		mockMvc.perform(get("/api/projects/{projectId}/layers", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].basemap").doesNotExist())
				.andExpect(jsonPath("$[0].basemapOpacity").doesNotExist());
	}

	@Test
	void setsAndReadsBothFields() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"opentopo\", \"basemapOpacity\": 0.6 }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").value("opentopo"))
				.andExpect(jsonPath("$.basemapOpacity").value(0.6));

		Layer reloaded = layerRepository.findById(layer.getId()).orElseThrow();
		assertThat(reloaded.getBasemap()).isEqualTo("opentopo");
		assertThat(reloaded.getBasemapOpacity()).isEqualTo(0.6);

		mockMvc.perform(get("/api/layers/{layerId}", layer.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").value("opentopo"))
				.andExpect(jsonPath("$.basemapOpacity").value(0.6));
	}

	@Test
	void nullResetsBothFieldsToFollowTheProjectAndTheResponseShowsIt() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"opentopo\", \"basemapOpacity\": 0.6 }"))
				.andExpect(status().isOk());

		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": null, \"basemapOpacity\": null }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").doesNotExist())
				.andExpect(jsonPath("$.basemapOpacity").doesNotExist());

		Layer reloaded = layerRepository.findById(layer.getId()).orElseThrow();
		assertThat(reloaded.getBasemap()).isNull();
		assertThat(reloaded.getBasemapOpacity()).isNull();
	}

	@Test
	void anAbsentFieldLeavesAPreviouslySetBasemapUnchanged() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"opentopo\", \"basemapOpacity\": 0.6 }"))
				.andExpect(status().isOk());

		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"visible\": false }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").value("opentopo"))
				.andExpect(jsonPath("$.basemapOpacity").value(0.6));
	}

	@Test
	void rejectsAnOpacityOutsideZeroToOne() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemapOpacity\": 1.5 }"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemapOpacity\": -0.1 }"))
				.andExpect(status().isBadRequest());
	}

	/**
	 * Befund 1 (Validierung, 27.08.): a token that is not one of the catalog's ids used
	 * to be accepted with 200 and stored forever, silently falling back to OSM on every
	 * client that ever read it -- see {@code doesNotValidateTheBasemapAgainstAnyCatalogue},
	 * this test's predecessor, which asserted exactly that as the intended behaviour.
	 *
	 * <p>Checks the message's prefix and suffix rather than its full text: {@link
	 * BasemapCatalog#unknownValueMessage} now names every catalog id (Aufgabe
	 * "Hintergrundkarten-Katalog", 27.08.), and asserting that whole list here would make
	 * this test change every time an id is added, for no reason this test cares about.
	 */
	@Test
	void rejectsAnUnknownBasemapToken() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"ein-unbekannter-dienst\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.basemap")
						.value(Matchers.startsWith(
								"Unbekannte Hintergrundkarte: ein-unbekannter-dienst. Gültig sind osm, ")))
				.andExpect(jsonPath("$.errors.basemap")
						.value(Matchers.endsWith(
								"oder eine URL-Vorlage, die mit https:// beginnt und entweder {z}, {x} und {y} "
								+ "oder {bbox-epsg-3857} enthält.")));

		Layer reloaded = layerRepository.findById(layer.getId()).orElseThrow();
		assertThat(reloaded.getBasemap()).isNull();
	}

	@Test
	void acceptsEveryKnownBasemapToken() throws Exception {
		for (String token : new String[] { "osm", "osm-light", "osm-dark", "opentopo", "none" }) {
			mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{ \"basemap\": \"" + token + "\" }"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.basemap").value(token));
		}
	}

	/**
	 * The full catalog is what actually matters (Aufgabe "Hintergrundkarten-Katalog"):
	 * every id {@link BasemapCatalog} declares must be accepted on a layer too, not just
	 * the five that predate it.
	 */
	@Test
	void acceptsEveryCatalogId() throws Exception {
		for (String id : BasemapCatalog.list().stream().map(entry -> entry.id()).toList()) {
			mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{ \"basemap\": \"" + id + "\" }"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.basemap").value(id));
		}
	}

	/**
	 * Setzen: die bestehenden Endpunkte (VERTRAG.md) -- a value starting with
	 * {@code https://} is a free-text tile-URL template, not a catalog id.
	 */
	@Test
	void acceptsAValidUrlTemplate() throws Exception {
		String url = "https://tiles.example.org/{z}/{x}/{y}.png";
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"" + url + "\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").value(url));

		Layer reloaded = layerRepository.findById(layer.getId()).orElseThrow();
		assertThat(reloaded.getBasemap()).isEqualTo(url);
	}

	@Test
	void rejectsAnHttpUrlTemplate() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"http://tiles.example.org/{z}/{x}/{y}.png\" }"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAnHttpsUrlWithoutPlaceholders() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"https://tiles.example.org/fixed.png\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.basemap")
						.value("Die URL-Vorlage muss entweder {z}, {x} und {y} oder {bbox-epsg-3857} enthalten."));
	}

	/**
	 * Form B (VERTRAG.md "Zwei Formen von urlTemplate", 27.08.) -- a WMS-GetMap template
	 * with {@code {bbox-epsg-3857}} instead of the tile triple.
	 */
	@Test
	void acceptsAWmsGetMapUrlTemplate() throws Exception {
		String url = "https://geodienste.hamburg.de/wms_dop?SERVICE=WMS&REQUEST=GetMap&BBOX={bbox-epsg-3857}";
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"" + url + "\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").value(url));

		Layer reloaded = layerRepository.findById(layer.getId()).orElseThrow();
		assertThat(reloaded.getBasemap()).isEqualTo(url);
	}

	@Test
	void rejectsAnHttpsUrlWithCredentials() throws Exception {
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"https://user:pass@tiles.example.org/{z}/{x}/{y}.png\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.basemap")
						.value("Die URL-Vorlage darf keine Zugangsdaten enthalten."));
	}

	@Test
	void rejectsAnOverlongUrlTemplate() throws Exception {
		String url = "https://tiles.example.org/" + "a".repeat(2000) + "/{z}/{x}/{y}.png";
		mockMvc.perform(patch("/api/layers/{layerId}", layer.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"" + url + "\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.basemap")
						.value("Die URL-Vorlage darf höchstens 2000 Zeichen lang sein."));
	}
}
