package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.basemap.BasemapCatalog;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * A project's opacity for its basemap (CONTRACT.md phase 18) -- unlike a layer's, it is
 * never null: a project always has a basemap, so it always has an opacity for it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectBasemapTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProjectRepository projectRepository;

	private Project project;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Karten-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));
	}

	@AfterEach
	void tearDown() {
		projectRepository.deleteById(project.getId());
	}

	@Test
	void defaultsToFullOpacity() throws Exception {
		mockMvc.perform(get("/api/projects/{id}", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemapOpacity").value(1.0));
	}

	@Test
	void setsAndReadsTheOpacity() throws Exception {
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemapOpacity\": 0.75 }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemapOpacity").value(0.75));

		Project reloaded = projectRepository.findById(project.getId()).orElseThrow();
		assertThat(reloaded.getBasemapOpacity()).isEqualTo(0.75);

		mockMvc.perform(get("/api/projects/{id}", project.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemapOpacity").value(0.75));
	}

	@Test
	void anAbsentFieldLeavesThePreviousOpacityUnchanged() throws Exception {
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemapOpacity\": 0.75 }"))
				.andExpect(status().isOk());

		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Umbenannt " + UUID.randomUUID() + "\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemapOpacity").value(0.75));
	}

	@Test
	void rejectsAnOpacityOutsideZeroToOne() throws Exception {
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemapOpacity\": 1.5 }"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemapOpacity\": -0.1 }"))
				.andExpect(status().isBadRequest());
	}

	/**
	 * Befund 1 (Validierung, 27.08.): {@code PATCH .../projects/{id}} with
	 * {@code basemap: "grayscale"} used to be accepted with 200 and stored forever --
	 * {@code frontend/src/map/basemap.ts:171} silently falls back to OSM for any token it
	 * does not recognise, so the caller never learns the write did not do what it asked.
	 *
	 * <p>Checks the message's prefix and suffix rather than its full text: {@link
	 * BasemapCatalog#unknownValueMessage} now names every catalog id (Aufgabe
	 * "Hintergrundkarten-Katalog", 27.08.), and asserting that whole list here would make
	 * this test change every time an id is added, for no reason this test cares about.
	 */
	@Test
	void rejectsAnUnknownBasemapToken() throws Exception {
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"grayscale\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.basemap")
						.value(Matchers.startsWith("Unbekannte Hintergrundkarte: grayscale. Gültig sind osm, ")))
				.andExpect(jsonPath("$.errors.basemap")
						.value(Matchers.endsWith(
								"oder eine URL-Vorlage, die mit https:// beginnt und entweder {z}, {x} und {y} "
								+ "oder {bbox-epsg-3857} enthält.")));

		Project reloaded = projectRepository.findById(project.getId()).orElseThrow();
		assertThat(reloaded.getBasemap()).isEqualTo("osm");
	}

	@Test
	void acceptsEveryKnownBasemapToken() throws Exception {
		for (String token : new String[] { "osm", "osm-light", "osm-dark", "opentopo", "none" }) {
			mockMvc.perform(patch("/api/projects/{id}", project.getId())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{ \"basemap\": \"" + token + "\" }"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.basemap").value(token));
		}
	}

	/**
	 * The same check at creation time -- {@code ProjectService#create} passes {@code
	 * request.basemap()} straight into the {@link Project} constructor, so an unknown
	 * token there used to end up on row one, never even reaching {@code update}.
	 */
	@Test
	void rejectsAnUnknownBasemapTokenAtCreation() throws Exception {
		mockMvc.perform(post("/api/projects")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"Neues Projekt " + UUID.randomUUID()
								+ "\", \"basemap\": \"grayscale\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.basemap")
						.value(Matchers.startsWith("Unbekannte Hintergrundkarte: grayscale. Gültig sind osm, ")));
	}

	/**
	 * The full catalog is what actually matters (Aufgabe "Hintergrundkarten-Katalog"):
	 * every id {@link BasemapCatalog} declares must be accepted, not just the five that
	 * predate it.
	 */
	@Test
	void acceptsEveryCatalogId() throws Exception {
		for (String id : BasemapCatalog.list().stream().map(entry -> entry.id()).toList()) {
			mockMvc.perform(patch("/api/projects/{id}", project.getId())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{ \"basemap\": \"" + id + "\" }"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.basemap").value(id));
		}
	}

	/**
	 * Setzen: die bestehenden Endpunkte (VERTRAG.md) -- a value starting with
	 * {@code https://} is a free-text tile-URL template, not a catalog id, and is
	 * accepted as long as it carries {@code {z}}, {@code {x}} and {@code {y}}.
	 */
	@Test
	void acceptsAValidUrlTemplate() throws Exception {
		String url = "https://tiles.example.org/{z}/{x}/{y}.png";
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"" + url + "\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").value(url));

		Project reloaded = projectRepository.findById(project.getId()).orElseThrow();
		assertThat(reloaded.getBasemap()).isEqualTo(url);
	}

	@Test
	void rejectsAnHttpUrlTemplate() throws Exception {
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"http://tiles.example.org/{z}/{x}/{y}.png\" }"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAJavascriptScheme() throws Exception {
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"javascript:alert(1)\" }"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAnHttpsUrlWithoutPlaceholders() throws Exception {
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"https://tiles.example.org/fixed.png\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.basemap")
						.value("Die URL-Vorlage muss entweder {z}, {x} und {y} oder {bbox-epsg-3857} enthalten."));
	}

	/**
	 * Form B (VERTRAG.md "Zwei Formen von urlTemplate", 27.08.) -- a WMS-GetMap template
	 * with {@code {bbox-epsg-3857}} instead of the tile triple, the shape Hamburg's
	 * aerial imagery needs.
	 */
	@Test
	void acceptsAWmsGetMapUrlTemplate() throws Exception {
		String url = "https://geodienste.hamburg.de/wms_dop?SERVICE=WMS&REQUEST=GetMap&BBOX={bbox-epsg-3857}";
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"" + url + "\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemap").value(url));

		Project reloaded = projectRepository.findById(project.getId()).orElseThrow();
		assertThat(reloaded.getBasemap()).isEqualTo(url);
	}

	@Test
	void rejectsAnHttpsUrlWithCredentials() throws Exception {
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"https://user:pass@tiles.example.org/{z}/{x}/{y}.png\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.basemap")
						.value("Die URL-Vorlage darf keine Zugangsdaten enthalten."));

		Project reloaded = projectRepository.findById(project.getId()).orElseThrow();
		assertThat(reloaded.getBasemap()).isEqualTo("osm");
	}

	@Test
	void rejectsAnOverlongUrlTemplate() throws Exception {
		String url = "https://tiles.example.org/" + "a".repeat(2000) + "/{z}/{x}/{y}.png";
		mockMvc.perform(patch("/api/projects/{id}", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"basemap\": \"" + url + "\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.basemap")
						.value("Die URL-Vorlage darf höchstens 2000 Zeichen lang sein."));
	}
}
