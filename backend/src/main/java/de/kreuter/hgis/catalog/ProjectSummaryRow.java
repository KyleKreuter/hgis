package de.kreuter.hgis.catalog;

import java.time.Instant;
import java.util.UUID;

/**
 * Spring Data projection for the aggregate query behind the project browser.
 * Kept as an interface so the driver maps the result set directly, without an
 * intermediate entity.
 *
 * <p>{@code center} and {@code extent} arrive as their coordinates rather than as JTS
 * geometries -- a native query's interface projection maps scalar columns, and
 * {@code ST_X}/{@code ST_Y}/{@code ST_XMin}/... turn the two geometry columns into
 * exactly the doubles {@link de.kreuter.hgis.catalog.dto.ProjectDtos.Summary} needs.
 */
public interface ProjectSummaryRow {

	UUID getId();

	String getName();

	String getDescription();

	int getSrid();

	Instant getLastOpenedAt();

	Instant getCreatedAt();

	long getLayerCount();

	long getFeatureCount();

	/** Null when the project has no saved center. */
	Double getCenterLng();

	Double getCenterLat();

	/** Null when the project has no saved zoom. */
	Double getZoom();

	/** Null when the project has no saved extent; all four are set together. */
	Double getExtentMinLng();

	Double getExtentMinLat();

	Double getExtentMaxLng();

	Double getExtentMaxLat();

	String getBasemap();
}
