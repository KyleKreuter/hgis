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

	/**
	 * One row of the catalog list (CONTRACT.md 11.2).
	 *
	 * @param collectionCount CONTRACT.md 11.9: {@code 1} for a row that is one collection --
	 *                        the overwhelming majority -- and the real count for a service
	 *                        listed as one row. Greater than {@code 1} is the client's only
	 *                        signal, and its whole signal, for "pick a collection before
	 *                        importing".
	 */
	public record DatasetSummary(
			String id,
			String title,
			String description,
			String kind,
			String agency,
			String topic,
			Long featureCount,
			double[] bbox,
			int collectionCount,

			/**
			 * The CSV's {@code WMS-Adresse} column, or null (plan "Kartenbilder aus dem
			 * Geoportal Hamburg", stage 2): lets the map-image dialog fetch this dataset's
			 * WMS capabilities for a {@code kind} of {@code WMS} or {@code BOTH}.
			 */
			String wmsUrl) {
	}

	/**
	 * Answer to 11.4: {@link DatasetSummary} plus what only the live service can say.
	 * Kept as its own record rather than {@code DatasetSummary} plus an extra object, to
	 * match the CONTRACT.md shape exactly -- the frontend reads one flat object either way.
	 *
	 * @param collectionCount as on {@link DatasetSummary}, since 11.4 is "everything from
	 *                        11.2 plus" -- a detail fetched on its own is otherwise unable to
	 *                        say what it is
	 * @param collections     CONTRACT.md 11.9: filled only for a service with
	 *                        {@code collectionCount > 1}; empty, never null, otherwise. For
	 *                        such a service {@code fields}, {@code featureCount} and
	 *                        {@code sourceFeatureIdField} stay empty -- they describe one
	 *                        collection, and none is chosen yet. The client asks for the
	 *                        detail again with the chosen collection's id to get them.
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
			List<Field> fields,
			int collectionCount,
			List<CollectionRef> collections,

			/** @see DatasetSummary#wmsUrl() */
			String wmsUrl) {
	}

	/** One entry of {@link DatasetDetail#fields()}. */
	public record Field(String name, String title, String dataType, List<String> values) {
	}

	/**
	 * One collection to pick from a service listed as one row (CONTRACT.md 11.9). {@code id}
	 * is a dataset id like any other: it is what a detail or an import call names.
	 */
	public record CollectionRef(String id, String title) {
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
