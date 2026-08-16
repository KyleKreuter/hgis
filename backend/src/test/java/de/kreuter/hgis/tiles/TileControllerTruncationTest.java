package de.kreuter.hgis.tiles;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.LayerStyleService;
import de.kreuter.hgis.catalog.Project;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link TileController}'s side of the tile-size finding (CONTRACT.md): whether the
 * {@code X-Tile-Truncated} header is set follows {@link MvtService.RenderedTile#truncated()}
 * exactly, with nothing this controller adds or drops on the way. {@link MvtService} itself
 * -- what actually decides whether a tile is truncated -- is covered separately by
 * {@code MvtServiceTest}; this class only needs a mocked one, so it runs as a
 * {@code @WebMvcTest} slice, no database required.
 */
@WebMvcTest(controllers = TileController.class)
class TileControllerTruncationTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MvtService mvtService;

	@MockitoBean
	private LayerRepository layerRepository;

	@MockitoBean
	private LayerStyleService styleService;

	private Layer layer;

	@BeforeEach
	void setUp() {
		Project project = new Project("Truncation-Test", null, 25832, "osm");
		UUID layerId = UUID.randomUUID();
		layer = new Layer(layerId, project, "Truncation-Test",
				"layer_" + layerId.toString().replace("-", ""), "MULTIPOINT", 25832);

		given(layerRepository.findById(layerId)).willReturn(Optional.of(layer));
		given(layerRepository.findClipMasks(any())).willReturn(List.of());
		given(styleService.tileColumns(layer)).willReturn(Set.of());
	}

	@Test
	@DisplayName("a truncated tile carries the X-Tile-Truncated header")
	void truncatedTileCarriesTheHeader() throws Exception {
		given(mvtService.renderTile(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(),
				org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.anyInt()))
				.willReturn(new MvtService.RenderedTile(new byte[] { 1, 2, 3 }, true));

		mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt", layer.getId(), 10, 0, 0))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Tile-Truncated", "true"));
	}

	@Test
	@DisplayName("a complete tile carries no X-Tile-Truncated header at all")
	void completeTileCarriesNoTruncationHeader() throws Exception {
		given(mvtService.renderTile(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(),
				org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.anyInt()))
				.willReturn(new MvtService.RenderedTile(new byte[] { 1, 2, 3 }, false));

		mockMvc.perform(get("/api/layers/{layerId}/tiles/{z}/{x}/{y}.mvt", layer.getId(), 10, 0, 0))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("X-Tile-Truncated"));
	}
}
