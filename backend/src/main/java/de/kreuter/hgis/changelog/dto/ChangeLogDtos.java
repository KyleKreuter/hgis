package de.kreuter.hgis.changelog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.UUID;

/** Transport types for the write change log (CONTRACT.md "Schreibstufe" 1.2). */
public final class ChangeLogDtos {

	private ChangeLogDtos() {
	}

	/**
	 * One row of {@code GET /api/projects/{projectId}/changes}, newest first.
	 *
	 * @param layerId      null exactly for a {@code layer.purge} entry -- see {@link
	 *                     de.kreuter.hgis.changelog.ChangeLogEntry#getLayerId()}
	 * @param layerName    the layer's name as it stood at the moment of this write,
	 *                     readable even after the layer itself is long gone
	 * @param action       one of {@link de.kreuter.hgis.changelog.ChangeLogAction}'s ten
	 *                     tokens
	 * @param clientName   the {@code X-Hgis-Client} of whoever wrote it, or null when
	 *                     they named none
	 * @param affectedCount how many objects this one write touched
	 * @param deletedRows  for a {@code feature.delete} entry, the removed rows -- geometry
	 *                     as GeoJSON, attributes keyed by column_name -- as a raw JSON
	 *                     array; present only when the caller asked for it with {@code
	 *                     includeDeletedRows=true} (mirrors {@code GET .../features}'s own
	 *                     {@code geometry} parameter: cheap to browse by default, full
	 *                     detail on request). Always absent for every other action.
	 */
	public record Entry(
			UUID id,
			Instant occurredAt,
			UUID layerId,
			String layerName,
			String action,
			String clientName,
			int affectedCount,

			@JsonRawValue
			@JsonInclude(JsonInclude.Include.NON_NULL)
			String deletedRows) {
	}
}
