package de.kreuter.hgis.geoportal.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

/**
 * Transport types for the Geoportal API (CONTRACT.md phase 23). Grouped in one file the
 * same way as {@code LayerDtos} and {@code JobDtos}: small, closely related, always read
 * together.
 */
public final class GeoportalDtos {

	private GeoportalDtos() {
	}

	/** Answer to 11.2 and 11.3: the whole catalog, no paging. */
	public record CatalogResponse(Instant fetchedAt, List<DatasetSummary> datasets) {
	}

	/** One row of the catalog list (CONTRACT.md 11.2). */
	public record DatasetSummary(
			String id,
			String title,
			String description,
			String kind,
			String agency,
			String topic,
			Long featureCount,
			double[] bbox) {
	}

	/**
	 * Answer to 11.4: {@link DatasetSummary} plus what only the live service can say.
	 * Kept as its own record rather than {@code DatasetSummary} plus an extra object, to
	 * match the CONTRACT.md shape exactly -- the frontend reads one flat object either way.
	 */
	public record DatasetDetail(
			String id,
			String title,
			String description,
			String kind,
			String agency,
			String topic,
			Long featureCount,
			double[] bbox,
			String attribution,
			String licenseName,
			String licenseUrl,
			String datasetUri,
			String metadataUrl,
			Integer storageSrid,
			String sourceFeatureIdField,
			List<Field> fields) {
	}

	/** One entry of {@link DatasetDetail#fields()}. */
	public record Field(String name, String title, String dataType, List<String> values) {
	}

	/** Answer to 11.5. */
	public record CountResponse(Long featureCount) {
	}

	/** Body of 11.6. */
	public record ImportRequest(
			@NotBlank(message = "datasetId darf nicht leer sein")
			String datasetId,

			String name,

			/** {@code [minLng, minLat, maxLng, maxLat]} in EPSG:4326, or absent for the whole dataset. */
			double[] bbox,

			/** Technical field names, or absent for every field. The id field travels along regardless. */
			List<String> fields) {
	}
}
