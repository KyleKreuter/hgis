package de.kreuter.hgis.catalog;

import java.time.Instant;
import java.util.UUID;

/**
 * Spring Data projection for the aggregate query behind the project browser.
 * Kept as an interface so the driver maps the result set directly, without an
 * intermediate entity.
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
}
