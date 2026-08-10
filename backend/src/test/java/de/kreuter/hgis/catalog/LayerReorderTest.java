package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.List;
import java.util.UUID;
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
 * Tests for PUT /api/projects/{id}/layers/order.
 *
 * Separate from {@link LayerControllerTest} because reordering only says anything with
 * several layers present, while every test there works on exactly one. The annotations
 * match that class exactly, so both share one cached application context -- and with it
 * one Testcontainers database.
 *
 * No physical payload tables here: reordering touches the catalog and nothing else.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LayerReorderTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	private Project project;
	private Layer bottom;
	private Layer middle;
	private Layer top;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Reihenfolge " + UUID.randomUUID(), null, 25832, "osm"));
		bottom = saveLayer("Flurstücke", 0);
		middle = saveLayer("Straßen", 1);
		top = saveLayer("Gebäude", 2);
	}

	@AfterEach
	void tearDown() {
		layerRepository.deleteAll(List.of(bottom, middle, top));
		projectRepository.deleteById(project.getId());
	}

	private Layer saveLayer(String name, int zIndex) {
		UUID id = UUID.randomUUID();
		Layer layer = new Layer(id, project, name, SqlIdentifier.tableName(id), "MULTIPOLYGON", 25832);
		layer.setZIndex(zIndex);
		return layerRepository.saveAndFlush(layer);
	}

	private String orderBody(UUID... ids) {
		String quoted = java.util.Arrays.stream(ids)
				.map(id -> "\"" + id + "\"")
				.collect(java.util.stream.Collectors.joining(", "));
		return "{ \"layerIdsBottomToTop\": [" + quoted + "] }";
	}

	private int zIndexOf(Layer layer) {
		return layerRepository.findById(layer.getId()).orElseThrow().getZIndex();
	}

	@Test
	void writesTheNewOrderAndReturnsItBottomFirst() throws Exception {
		mockMvc.perform(put("/api/projects/{projectId}/layers/order", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(orderBody(top.getId(), bottom.getId(), middle.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].id").value(top.getId().toString()))
				.andExpect(jsonPath("$[1].id").value(bottom.getId().toString()))
				.andExpect(jsonPath("$[2].id").value(middle.getId().toString()));

		assertThat(zIndexOf(top)).isZero();
		assertThat(zIndexOf(bottom)).isEqualTo(1);
		assertThat(zIndexOf(middle)).isEqualTo(2);
	}

	@Test
	void rejectsAnIncompleteOrderWithoutChangingAnything() throws Exception {
		mockMvc.perform(put("/api/projects/{projectId}/layers/order", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(orderBody(top.getId(), bottom.getId())))
				.andExpect(status().isBadRequest());

		// The point of the batch endpoint: a rejected reorder leaves no layer moved.
		assertThat(zIndexOf(bottom)).isZero();
		assertThat(zIndexOf(middle)).isEqualTo(1);
		assertThat(zIndexOf(top)).isEqualTo(2);
	}

	@Test
	void rejectsADuplicatedLayer() throws Exception {
		mockMvc.perform(put("/api/projects/{projectId}/layers/order", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content(orderBody(top.getId(), top.getId(), bottom.getId())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsALayerFromAnotherProject() throws Exception {
		Project other = projectRepository.saveAndFlush(
				new Project("Fremd " + UUID.randomUUID(), null, 25832, "osm"));
		UUID foreignId = UUID.randomUUID();
		Layer foreign = layerRepository.saveAndFlush(new Layer(
				foreignId, other, "Fremdlayer", SqlIdentifier.tableName(foreignId), "MULTIPOLYGON", 25832));

		try {
			mockMvc.perform(put("/api/projects/{projectId}/layers/order", project.getId())
							.contentType(MediaType.APPLICATION_JSON)
							.content(orderBody(bottom.getId(), middle.getId(), foreign.getId())))
					.andExpect(status().isBadRequest());

			assertThat(zIndexOf(top)).isEqualTo(2);
		}
		finally {
			layerRepository.delete(foreign);
			projectRepository.deleteById(other.getId());
		}
	}

	@Test
	void rejectsAnEmptyOrder() throws Exception {
		mockMvc.perform(put("/api/projects/{projectId}/layers/order", project.getId())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"layerIdsBottomToTop\": [] }"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void returnsNotFoundForAnUnknownProject() throws Exception {
		mockMvc.perform(put("/api/projects/{projectId}/layers/order", UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content(orderBody(bottom.getId())))
				.andExpect(status().isNotFound());
	}
}
