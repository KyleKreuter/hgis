package de.kreuter.hgis.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.changelog.ChangeLogAction;
import de.kreuter.hgis.changelog.ChangeLogEntry;
import de.kreuter.hgis.changelog.ChangeLogRepository;
import de.kreuter.hgis.common.LayerProvenance;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.geoportal.GeoportalDatasetService;
import de.kreuter.hgis.wms.dto.WmsDtos;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CONTRACT.md 3: creating a map image layer. {@link WmsCapabilitiesService} and
 * {@link GeoportalDatasetService} are mocked -- their own behaviour is covered by their
 * own test suites, this one is about {@link MapLayerService} turning a capabilities
 * answer into a stored {@link Layer} correctly, with the real database underneath to
 * exercise the actual {@code wms_layers text[]} column mapping and CHECK constraints.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MapLayerControllerTest {

	private static final String SERVICE_URL = "https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private ChangeLogRepository changeLogRepository;

	@MockitoBean
	private WmsCapabilitiesService capabilitiesService;

	@MockitoBean
	private GeoportalDatasetService geoportalDatasetService;

	private Project project;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Kartenbild-Anlage " + UUID.randomUUID(), null, 25832, "osm"));
	}

	@AfterEach
	void tearDown() {
		layerRepository.findByProjectOrdered(project.getId()).forEach(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	private static WmsDtos.CapabilitiesResponse twoLayerCapabilities() {
		WmsDtos.Layer stadtplan = new WmsDtos.Layer("stadtplan", "Stadtplan", 0, true,
				"https://geodienste.hamburg.de/legend.png", 1000.0, 50000.0,
				new double[] { 9.6, 53.3, 10.4, 53.8 });
		WmsDtos.Layer beschriftung = new WmsDtos.Layer("beschriftung", "Beschriftung", 0, false, null, null, null,
				new double[] { 9.7, 53.4, 10.2, 53.7 });
		return new WmsDtos.CapabilitiesResponse(SERVICE_URL, "WMS Cache Stadtplan Hamburg", "1.3.0",
				List.of("image/png", "image/jpeg"), List.of(stadtplan, beschriftung));
	}

	private String requestBody(String layersJson, String name, String datasetId) {
		return """
				{"serviceUrl":"%s","layers":%s,"imageFormat":"image/png"%s%s}
				"""
				.formatted(SERVICE_URL, layersJson,
						name == null ? "" : ",\"name\":\"" + name + "\"",
						datasetId == null ? "" : ",\"datasetId\":\"" + datasetId + "\"");
	}

	@Test
	@DisplayName("creates a map image layer, filling extent, legendUrl, queryable and the zoom window from capabilities")
	void createsAMapImageLayerFromCapabilities() throws Exception {
		given(capabilitiesService.capabilities(SERVICE_URL)).willReturn(twoLayerCapabilities());

		String result = mockMvc.perform(post("/api/projects/{projectId}/map-layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(requestBody("[\"stadtplan\",\"beschriftung\"]", "Internetstadtplan Hamburg", null)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.kind").value("WMS"))
				.andExpect(jsonPath("$.name").value("Internetstadtplan Hamburg"))
				.andExpect(jsonPath("$.wms.serviceUrl").value(SERVICE_URL))
				.andExpect(jsonPath("$.wms.layers[0]").value("stadtplan"))
				.andExpect(jsonPath("$.wms.layers[1]").value("beschriftung"))
				.andExpect(jsonPath("$.wms.imageFormat").value("image/png"))
				.andExpect(jsonPath("$.wms.legendUrl").value("https://geodienste.hamburg.de/legend.png"))
				.andExpect(jsonPath("$.wms.queryable").value(true))
				.andExpect(header().exists("Location"))
				.andReturn().getResponse().getContentAsString();

		UUID layerId = UUID.fromString(tools.jackson.databind.json.JsonMapper.builder().build()
				.readTree(result).get("id").asString());
		Layer stored = layerRepository.findById(layerId).orElseThrow();
		assertThat(stored.getExtent()).as("union of both layers' bboxes").isNotNull();
		assertThat(stored.getExtent().getEnvelopeInternal().getMinX()).isEqualTo(9.6);
		assertThat(stored.getExtent().getEnvelopeInternal().getMaxX()).isEqualTo(10.4);
		// stadtplan alone declares a scale range; beschriftung's is null and contributes
		// nothing, so the combined window is exactly stadtplan's own.
		assertThat(stored.getMinZoom()).isBetween(0, 22);
		assertThat(stored.getMaxZoom()).isBetween(stored.getMinZoom(), 22);
		assertThat(stored.getSourceDatasetId()).as("no datasetId given, so no provenance").isNull();

		// A map image is a layer coming into existence just as much as an import or a
		// hand-drawn one, and CONTRACT.md's change log does not carve out an exception
		// for how a write got there.
		List<ChangeLogEntry> entries = changeLogRepository
				.findByProjectIdOrderByOccurredAtDescIdDesc(project.getId(), org.springframework.data.domain.PageRequest.of(0, 10))
				.stream()
				.filter(e -> layerId.equals(e.getLayerId()))
				.toList();
		assertThat(entries).extracting(ChangeLogEntry::getAction).containsExactly(ChangeLogAction.LAYER_CREATE);
		assertThat(entries.get(0).getAffectedCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("no name given falls back to the first chosen layer's title")
	void defaultsNameToTheFirstChosenLayersTitle() throws Exception {
		given(capabilitiesService.capabilities(SERVICE_URL)).willReturn(twoLayerCapabilities());

		mockMvc.perform(post("/api/projects/{projectId}/map-layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(requestBody("[\"stadtplan\",\"beschriftung\"]", null, null)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Stadtplan"));
	}

	@Test
	@DisplayName("a null entry in the requested layer list is a 400, not an internal error")
	void aNullLayerEntryInTheRequestIsBadRequest() throws Exception {
		given(capabilitiesService.capabilities(SERVICE_URL)).willReturn(twoLayerCapabilities());

		mockMvc.perform(post("/api/projects/{projectId}/map-layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(requestBody("[\"stadtplan\", null]", null, null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("nichts zu zeichnen")));

		assertThat(layerRepository.findByProjectOrdered(project.getId())).isEmpty();
	}

	/**
	 * Orchestrator amendment: the capabilities answer can now hold an unnamed grouping
	 * layer (name null) mixed in with the pickable ones -- resolving the client's chosen
	 * names against that list must not throw on the group heading it never asked for.
	 */
	@Test
	@DisplayName("an unnamed group heading in the capabilities answer does not break resolving the chosen layers")
	void anUnnamedGroupHeadingInCapabilitiesDoesNotBreakResolution() throws Exception {
		WmsDtos.Layer heading = new WmsDtos.Layer(null, "Gruppe ohne Namen", 0, false, null, null, null, null);
		WmsDtos.Layer stadtplan = new WmsDtos.Layer("stadtplan", "Stadtplan", 1, true, null, null, null,
				new double[] { 9.6, 53.3, 10.4, 53.8 });
		given(capabilitiesService.capabilities(SERVICE_URL)).willReturn(new WmsDtos.CapabilitiesResponse(
				SERVICE_URL, "WMS Cache Stadtplan Hamburg", "1.3.0", List.of("image/png"),
				List.of(heading, stadtplan)));

		mockMvc.perform(post("/api/projects/{projectId}/map-layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(requestBody("[\"stadtplan\"]", null, null)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.wms.layers[0]").value("stadtplan"));
	}

	@Test
	@DisplayName("a layer name the service does not offer is a 400")
	void anUnknownLayerNameIsBadRequest() throws Exception {
		given(capabilitiesService.capabilities(SERVICE_URL)).willReturn(twoLayerCapabilities());

		mockMvc.perform(post("/api/projects/{projectId}/map-layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(requestBody("[\"unbekannt\"]", null, null)))
				.andExpect(status().isBadRequest());

		assertThat(layerRepository.findByProjectOrdered(project.getId())).isEmpty();
	}

	@Test
	@DisplayName("an image format the service does not offer is a 400")
	void anUnknownImageFormatIsBadRequest() throws Exception {
		given(capabilitiesService.capabilities(SERVICE_URL)).willReturn(twoLayerCapabilities());

		mockMvc.perform(post("/api/projects/{projectId}/map-layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"serviceUrl":"%s","layers":["stadtplan"],"imageFormat":"image/tiff"}
								""".formatted(SERVICE_URL)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void returnsNotFoundForAnUnknownProject() throws Exception {
		mockMvc.perform(post("/api/projects/{projectId}/map-layers", UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content(requestBody("[\"stadtplan\"]", null, null)))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("a 422 from the capabilities re-check (e.g. no EPSG:3857) reaches the client unchanged")
	void aCapabilitiesRejectionSurfacesAsIs() throws Exception {
		given(capabilitiesService.capabilities(SERVICE_URL))
				.willThrow(new de.kreuter.hgis.common.UnprocessableEntityException(
						"Dieser Dienst liefert keine Karten in Web-Mercator (EPSG:3857). hGIS kann ihn nicht anzeigen."));

		mockMvc.perform(post("/api/projects/{projectId}/map-layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(requestBody("[\"stadtplan\"]", null, null)))
				.andExpect(status().isUnprocessableEntity());
	}

	@Test
	@DisplayName("a datasetId writes the Geoportal catalog's provenance onto the layer, like a vector import")
	void aDatasetIdWritesProvenanceOntoTheLayer() throws Exception {
		given(capabilitiesService.capabilities(SERVICE_URL)).willReturn(twoLayerCapabilities());
		String datasetId = "md:0cbaa166-3a14-4b94-bddd-df3d1b502bcf";
		given(geoportalDatasetService.provenanceFor(datasetId)).willReturn(new LayerProvenance(
				"Freie und Hansestadt Hamburg, LGV", "Datenlizenz Deutschland – Namensnennung – Version 2.0",
				"https://www.govdata.de/dl-de/by-2-0", "https://registry.gdi-de.org/id/de.hh/x",
				"https://metaver.de/trefferanzeige?docuuid=x", datasetId, null,
				Instant.parse("2026-08-13T09:00:00Z")));

		String result = mockMvc.perform(post("/api/projects/{projectId}/map-layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(requestBody("[\"stadtplan\"]", null, datasetId)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		UUID layerId = UUID.fromString(tools.jackson.databind.json.JsonMapper.builder().build()
				.readTree(result).get("id").asString());
		Layer stored = layerRepository.findById(layerId).orElseThrow();
		assertThat(stored.getSourceAttribution()).isEqualTo("Freie und Hansestadt Hamburg, LGV");
		assertThat(stored.getSourceDatasetId()).isEqualTo(datasetId);
		assertThat(stored.getSourceFeatureIdField()).as("a map image has no attribute table").isNull();
	}

	@Test
	@DisplayName("an unknown datasetId is a 404, and nothing is stored")
	void anUnknownDatasetIdIsNotFound() throws Exception {
		given(capabilitiesService.capabilities(SERVICE_URL)).willReturn(twoLayerCapabilities());
		given(geoportalDatasetService.provenanceFor(eq("md:unbekannt")))
				.willThrow(new NotFoundException("Geoportal-Datensatz md:unbekannt existiert nicht"));

		mockMvc.perform(post("/api/projects/{projectId}/map-layers", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(requestBody("[\"stadtplan\"]", null, "md:unbekannt")))
				.andExpect(status().isNotFound());

		assertThat(layerRepository.findByProjectOrdered(project.getId())).isEmpty();
	}
}
