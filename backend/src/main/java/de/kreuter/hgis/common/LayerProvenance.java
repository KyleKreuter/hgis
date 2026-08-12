package de.kreuter.hgis.common;

import java.time.Instant;

/**
 * Where a layer's data came from, for a layer imported from an external service rather than
 * drawn or uploaded by hand (CONTRACT.md phase 23.7). Written once, at import time, by
 * {@code de.kreuter.hgis.ingest.ImportTransactions#begin} in the same transaction as the
 * layer row itself -- the only place that can be atomic with layer creation, since the
 * layer's id does not exist before it.
 *
 * <p>Lives in {@code common} rather than {@code geoportal}, the same way {@link
 * GeometryType} does: {@code ingest} needs the type to carry it through {@link
 * de.kreuter.hgis.ingest.ImportService#runImportAsync}, and {@code ingest} must not depend
 * on {@code geoportal} -- that dependency only ever runs the other way.
 *
 * @param datasetId      the Geoportal catalog id this layer was imported from, e.g.
 *                       {@code strassenbaumkataster/strassenbaumkataster_hh}; exists for
 *                       stage 5's future reconcile and is shown nowhere (CONTRACT.md 11.7)
 * @param featureIdField technical name of the field carrying the service's own stable
 *                       feature id (decision E6), or null when the collection has none;
 *                       exists for the same reason as {@code datasetId}
 */
public record LayerProvenance(
		String attribution,
		String licenseName,
		String licenseUrl,
		String datasetUri,
		String metadataUrl,
		String datasetId,
		String featureIdField,
		Instant fetchedAt) {
}
