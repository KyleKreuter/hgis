package de.kreuter.hgis.ingest.spi;

import de.kreuter.hgis.common.GeometryType;
import java.util.List;

/**
 * What a reader discovered about a source file, before a single feature is written.
 *
 * This is the contract between the reading side (which knows Shapefiles, GeoPackages
 * and CSV) and the writing side (which knows DDL and PostGIS). Neither needs to know
 * anything about the other.
 *
 * @param geometryType  target geometry type, always a multi variant or GEOMETRY --
 *                      single geometries get promoted with ST_Multi on insert, because
 *                      a Shapefile makes no distinction and one stray multipolygon must
 *                      not fail the whole import
 * @param sourceSrid    CRS the coordinates are in, as detected or chosen by the user
 * @param fields        attributes in file order
 * @param charset       encoding actually used for text values, for the import report
 * @param crsConfidence how the source CRS was established
 * @param featureCount  total if the format knows it up front, otherwise null
 */
public record SourceSchema(
		GeometryType geometryType,
		int sourceSrid,
		List<SourceField> fields,
		String charset,
		CrsConfidence crsConfidence,
		Long featureCount) {

	/**
	 * Where the source CRS came from. DECLARED is trustworthy; GUESSED means a
	 * plausibility check picked it and the user should confirm before data is written.
	 */
	public enum CrsConfidence {
		/** Read from .prj, the GeoPackage SRS table or an explicit user choice. */
		DECLARED,
		/** Assumed per format convention, e.g. GeoJSON without a crs member. */
		ASSUMED,
		/** Derived from the coordinate ranges because nothing else was available. */
		GUESSED
	}
}
