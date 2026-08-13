package de.kreuter.hgis.features.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Transport types for the two structural operations of CONTRACT.md section 12.
 *
 * <p>Kept apart from {@link EditDtos} because they are not part of the edit batch and
 * never travel with one: both write straight through, since the geometry does not exist
 * until PostGIS has computed it.
 */
public final class SplitMergeDtos {

	private SplitMergeDtos() {
	}

	/**
	 * The cut for one saved feature.
	 *
	 * @param line GeoJSON {@code LineString} in EPSG:4326, like every other geometry on
	 *             the wire
	 * @param rowVersion the {@code xmin} that came with the feature; a mismatch means
	 *                   someone else wrote the row in the meantime. Omitting it skips the
	 *                   conflict check, exactly as in {@link EditDtos.Update}.
	 */
	public record SplitRequest(
			@NotNull(message = "Teilungslinie fehlt") JsonNode line,
			String rowVersion) {
	}

	/**
	 * @param fids the original first -- it keeps its fid -- then one entry per new part,
	 *             in ascending fid order
	 * @param dataVersion the layer's new tile cache buster; the client rebuilds its tile
	 *                    URL from it, which is what makes the change visible on the map
	 * @param featureCount the layer's recounted size, like {@link EditDtos.Response}
	 *                     carries -- a client should not have to re-read the catalog to
	 *                     learn a number this write already computed
	 */
	public record SplitResponse(List<Long> fids, long dataVersion, long featureCount) {
	}

	/**
	 * The features to join into one.
	 *
	 * @param fids between 2 and 100 saved features, duplicates ignored
	 * @param leadFid the feature that keeps its fid and every attribute value; must be one
	 *                of {@code fids}. The client names it explicitly -- the user picked it,
	 *                and order in a list is not a decision.
	 * @param rowVersions the {@code xmin} per fid, keyed by the fid as a string because
	 *                    JSON object keys are strings. A fid missing from the map skips
	 *                    its conflict check, as in {@link EditDtos.Update}.
	 */
	public record MergeRequest(
			@NotNull(message = "Auswahl fehlt") List<Long> fids,
			@NotNull(message = "Führendes Objekt fehlt") Long leadFid,
			Map<String, String> rowVersions) {

		/** Null-safe reading of {@link #rowVersions()}: absent means no check at all. */
		public Map<String, String> rowVersions() {
			return rowVersions == null ? Map.of() : rowVersions;
		}
	}

	/**
	 * @param fid the lead's fid, unchanged -- anything holding it stays valid
	 * @param dataVersion see {@link SplitResponse#dataVersion()}
	 * @param featureCount see {@link SplitResponse#featureCount()}
	 */
	public record MergeResponse(long fid, long dataVersion, long featureCount) {
	}
}
