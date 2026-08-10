package de.kreuter.hgis.features.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Transport types for the edit batch. */
public final class EditDtos {

	private EditDtos() {
	}

	/**
	 * One batch of changes, applied in a single transaction.
	 *
	 * <p>The client collects edits locally and sends them together rather than per
	 * change. That is what makes "save" a single point in time: either the whole batch
	 * lands or none of it does, and there is no state where half a user's work is on the
	 * server and the rest is in a browser tab.
	 */
	public record Request(
			List<Create> creates,
			List<Update> updates,
			List<Long> deletes,

			/**
			 * Repair invalid geometries with ST_MakeValid instead of rejecting them.
			 *
			 * <p>Off by default and only ever set by an explicit user action: repairing
			 * changes the data, and turning a self-intersecting polygon into something
			 * valid can also turn it into a different shape than the one that was drawn.
			 *
			 * <p>Boxed, not primitive. Jackson 3 turned FAIL_ON_NULL_FOR_PRIMITIVES on by
			 * default, so a primitive here would make the whole request unreadable
			 * whenever the field is simply absent -- which is the normal case.
			 */
			Boolean repairInvalid) {

		/** Null-safe reading of {@link #repairInvalid()}: absent means off. */
		public boolean repairsInvalid() {
			return Boolean.TRUE.equals(repairInvalid);
		}

		public List<Create> creates() {
			return creates == null ? List.of() : creates;
		}

		public List<Update> updates() {
			return updates == null ? List.of() : updates;
		}

		public List<Long> deletes() {
			return deletes == null ? List.of() : deletes;
		}
	}

	/**
	 * A new feature.
	 *
	 * @param clientId the negative placeholder the client used; echoed back in
	 *                 {@link Response#createdFids} so it can swap in the real fid
	 * @param geometry GeoJSON in EPSG:4326
	 * @param properties keyed by column_name, like everywhere else in the feature API
	 */
	public record Create(
			long clientId,
			@NotNull(message = "Geometrie fehlt") JsonNode geometry,
			Map<String, Object> properties) {
	}

	/**
	 * A changed feature.
	 *
	 * @param rowVersion the {@code xmin} that came with the feature; a mismatch means
	 *                   someone else wrote the row in the meantime
	 * @param geometry null leaves the geometry untouched -- an attribute-only edit
	 * @param properties null leaves all attributes untouched -- a geometry-only edit
	 */
	public record Update(
			long fid,
			String rowVersion,
			JsonNode geometry,
			Map<String, Object> properties) {
	}

	/**
	 * @param createdFids client placeholder -> assigned fid
	 * @param dataVersion the layer's new tile cache buster; the client rebuilds its tile
	 *                    URL from it, which is what makes the change visible on the map
	 */
	public record Response(
			Map<Long, Long> createdFids,
			int updated,
			int deleted,
			long dataVersion,
			long featureCount) {
	}
}
