package de.kreuter.hgis.catalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transport types for the layer API. Grouped in one file because they are small,
 * closely related and always read together -- mirrors {@code ProjectDtos}.
 */
public final class LayerDtos {

	private LayerDtos() {
	}

	/** Row in a project's layer list. */
	public record Summary(
			UUID id,
			String name,
			String geometryType,
			int srid,
			long featureCount,
			boolean visible,
			int zIndex,
			int minZoom,
			int maxZoom,
			long dataVersion,
			long styleVersion,

			/** [minLng, minLat, maxLng, maxLat] in EPSG:4326, or null if the layer is empty. */
			double[] extent) {
	}

	/** One entry of {@code LayerDetail.fields}. */
	public record Field(UUID id, String sourceName, String columnName, String dataType) {
	}

	/** Full layer, returned for a single layer. All of {@link Summary} plus attributes. */
	public record Detail(
			UUID id,
			String name,
			String geometryType,
			int srid,
			long featureCount,
			boolean visible,
			int zIndex,
			int minZoom,
			int maxZoom,
			long dataVersion,
			long styleVersion,
			double[] extent,
			List<Field> fields,

			/** Raw style JSON, reserved for phase 7. Always null until then. */
			String style,
			Instant createdAt,
			Instant updatedAt) {
	}

	/**
	 * Partial update. Every field is optional; null means "leave unchanged".
	 * srid and geometryType are deliberately absent -- they are immutable after creation.
	 */
	public record UpdateRequest(
			@Size(max = 200, message = "Name darf höchstens 200 Zeichen lang sein")
			String name,

			Boolean visible,

			Integer zIndex,

			@Min(value = 0, message = "minZoom muss zwischen 0 und 24 liegen")
			@Max(value = 24, message = "minZoom muss zwischen 0 und 24 liegen")
			Integer minZoom,

			@Min(value = 0, message = "maxZoom muss zwischen 0 und 24 liegen")
			@Max(value = 24, message = "maxZoom muss zwischen 0 und 24 liegen")
			Integer maxZoom) {
	}
}
