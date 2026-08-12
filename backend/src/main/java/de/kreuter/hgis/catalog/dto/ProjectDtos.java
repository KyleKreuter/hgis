package de.kreuter.hgis.catalog.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

			/** Opacity of the basemap itself, between 0 and 1. Every project has one. */
			double basemapOpacity,

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

			/** Opacity of the basemap itself. Null leaves it unchanged, like every other field here. */
			@DecimalMin(value = "0", message = "Deckkraft muss zwischen 0 und 1 liegen")
			@DecimalMax(value = "1", message = "Deckkraft muss zwischen 0 und 1 liegen")
			Double basemapOpacity,

			/** [lng, lat] in EPSG:4326. */
			double[] center,

			@DecimalMin(value = "0", message = "Zoom muss zwischen 0 und 24 liegen")
			@DecimalMax(value = "24", message = "Zoom muss zwischen 0 und 24 liegen")
			Double zoom) {
	}

	/** Answer to a delete preflight: what exactly would be destroyed. */
	public record DeletionImpact(long layerCount, long featureCount) {
	}

	public static final String QUERY_MODE_SEARCH = "search";
	public static final String QUERY_MODE_FILTER = "filter";

	/**
	 * What the client left open when it last left a project: which layer was active, and
	 * per layer what the attribute table was sorted, searched or filtered by, and which
	 * rows were selected.
	 *
	 * <p>{@code version} stays 1 regardless of what a client sends -- the server always
	 * writes its own canonical value, the same way {@code LayerStyleService} treats a
	 * style's version. A project that was never saved answers with {@link #empty()},
	 * never a 404.
	 */
	public record ViewState(Integer version, UUID activeLayerId, Map<UUID, LayerViewState> layers) {

		public ViewState {
			layers = layers == null ? Map.of() : Map.copyOf(layers);
		}

		public static ViewState empty() {
			return new ViewState(1, null, Map.of());
		}
	}

	/**
	 * @param sort      column and direction the attribute table was sorted by, or null
	 *                   for the default order. {@code field} is stored and returned
	 *                   as-is -- never checked against {@code layer_field}, since a
	 *                   field can be dropped after this was saved. The attribute
	 *                   table's own query already reports "Unbekanntes Sortierfeld"
	 *                   when that happens.
	 * @param query     search or filter text that was applied, or null
	 * @param selection fids that were selected; never null, an empty list by default
	 */
	public record LayerViewState(Sort sort, Query query, List<Long> selection) {

		public LayerViewState {
			selection = selection == null ? List.of() : List.copyOf(selection);
		}
	}

	public record Sort(String field, Boolean desc) {
	}

	/** @param mode {@value #QUERY_MODE_SEARCH} or {@value #QUERY_MODE_FILTER}, nothing else. */
	public record Query(String mode, String text) {
	}
}
