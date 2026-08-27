package de.kreuter.hgis.basemap;

import java.util.List;

/** Transport type for {@code GET /api/basemaps} (VERTRAG.md). */
public final class BasemapDtos {

	private BasemapDtos() {
	}

	public record CatalogResponse(List<BasemapEntry> basemaps) {
	}
}
