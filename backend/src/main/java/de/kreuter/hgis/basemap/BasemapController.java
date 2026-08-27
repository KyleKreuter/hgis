package de.kreuter.hgis.basemap;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/basemaps} (VERTRAG.md) -- the backend's basemap catalog, for the
 * frontend's picker and MCP's {@code list_basemaps} to read instead of each hardcoding
 * their own copy.
 */
@RestController
@RequestMapping("/api/basemaps")
public class BasemapController {

	@GetMapping
	public BasemapDtos.CatalogResponse list() {
		return new BasemapDtos.CatalogResponse(BasemapCatalog.list());
	}
}
