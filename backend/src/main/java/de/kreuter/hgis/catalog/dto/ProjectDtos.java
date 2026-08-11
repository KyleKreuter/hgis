package de.kreuter.hgis.catalog.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Transport types for the project API. Grouped in one file because they are small,
 * closely related and always read together.
 */
public final class ProjectDtos {

	private ProjectDtos() {
	}

	/** Row in the project browser. */
	public record Summary(
			UUID id,
			String name,
			String description,
			int srid,
			long layerCount,
			long featureCount,
			Instant lastOpenedAt,
			Instant createdAt) {
	}

	/** Full project, returned when a project is opened. */
	public record Detail(
			UUID id,
			String name,
			String description,
			int srid,
			String basemap,
			double[] center,
			Double zoom,
			double[] extent,
			long layerCount,
			long featureCount,
			Instant lastOpenedAt,
			Instant createdAt,
			Instant updatedAt) {
	}

	public record CreateRequest(
			@NotBlank(message = "Name darf nicht leer sein")
			@Size(max = 200, message = "Name darf höchstens 200 Zeichen lang sein")
			String name,

			@Size(max = 2000, message = "Beschreibung darf höchstens 2000 Zeichen lang sein")
			String description,

			/**
			 * Storage CRS. Null falls back to EPSG:25832. Validated against
			 * spatial_ref_sys rather than a hard coded list, so any CRS PROJ knows works.
			 */
			Integer srid,

			String basemap) {
	}

	/** Optional target name for an asynchronous project duplication. */
	public record DuplicateRequest(
			@Size(max = 200, message = "Name darf höchstens 200 Zeichen lang sein")
			String name) {
	}

	/**
	 * Partial update. Every field is optional; null means "leave unchanged".
	 * srid is deliberately absent -- it is immutable after creation.
	 */
	public record UpdateRequest(
			@Size(max = 200, message = "Name darf höchstens 200 Zeichen lang sein")
			String name,

			@Size(max = 2000, message = "Beschreibung darf höchstens 2000 Zeichen lang sein")
			String description,

			String basemap,

			/** [lng, lat] in EPSG:4326. */
			double[] center,

			@DecimalMin(value = "0", message = "Zoom muss zwischen 0 und 24 liegen")
			@DecimalMax(value = "24", message = "Zoom muss zwischen 0 und 24 liegen")
			Double zoom) {
	}

	/** Answer to a delete preflight: what exactly would be destroyed. */
	public record DeletionImpact(long layerCount, long featureCount) {
	}
}
