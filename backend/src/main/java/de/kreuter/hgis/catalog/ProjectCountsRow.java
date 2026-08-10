package de.kreuter.hgis.catalog;

/** Layer and feature totals for a single project. */
public interface ProjectCountsRow {

	long getLayerCount();

	long getFeatureCount();
}
