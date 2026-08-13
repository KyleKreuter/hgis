package de.kreuter.hgis.wms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** CONTRACT.md 3: creating a map image layer. */
public final class MapLayerDtos {

	private MapLayerDtos() {
	}

	/**
	 * @param serviceUrl the WMS service's address, with or without query parameters --
	 *                   read again through {@link de.kreuter.hgis.wms.WmsCapabilitiesService},
	 *                   the same as {@code GET /api/wms/capabilities} does
	 * @param layers     the chosen layer names, bottom first -- checked against the
	 *                   service's own capabilities before anything is stored
	 * @param imageFormat GetMap {@code FORMAT}; checked against the service's own list
	 * @param name       the layer's name in this project, or absent to take the title
	 *                   of the first chosen layer
	 * @param datasetId  the Geoportal catalog id this dataset came from, or absent for
	 *                   an address the user typed in by hand; when present, its licence
	 *                   and attribution are written onto the layer the same way a
	 *                   vector import already does (CONTRACT.md phase 23.7)
	 */
	public record CreateRequest(
			@NotBlank(message = "serviceUrl darf nicht leer sein")
			String serviceUrl,

			@NotEmpty(message = "Mindestens ein Layer des Dienstes muss gewählt werden")
			List<String> layers,

			@NotBlank(message = "imageFormat darf nicht leer sein")
			String imageFormat,

			String name,

			String datasetId) {
	}
}
