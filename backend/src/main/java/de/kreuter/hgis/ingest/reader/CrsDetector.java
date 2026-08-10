package de.kreuter.hgis.ingest.reader;

import de.kreuter.hgis.ingest.spi.SourceSchema.CrsConfidence;
import org.locationtech.jts.geom.Envelope;

/**
 * Turns a source CRS -- declared, format-assumed, or entirely absent -- into the single
 * {@code (srid, confidence)} pair a {@link de.kreuter.hgis.ingest.spi.SourceSchema}
 * carries.
 *
 * The plausibility check exists because a wrong default is worse than no default: a
 * GeoJSON file full of UTM coordinates silently read as EPSG:4326 places every feature off
 * the coast of Africa, and nothing about that failure looks like an error until someone
 * notices the map is empty.
 */
final class CrsDetector {

	/** Roughly Germany's UTM easting range, wide enough to cover neighbouring states too. */
	private static final double UTM_EASTING_MIN = 100_000;
	private static final double UTM_EASTING_MAX = 900_000;

	/** Northing range covering the whole of Germany, with a comfortable margin. */
	private static final double UTM_NORTHING_MIN = 5_000_000;
	private static final double UTM_NORTHING_MAX = 6_600_000;

	private CrsDetector() {
	}

	record Detection(int srid, CrsConfidence confidence) {
	}

	/** The format declared the CRS explicitly (.prj, GeoPackage SRS table, user override). */
	static Detection declared(int srid) {
		return new Detection(srid, CrsConfidence.DECLARED);
	}

	/**
	 * The format assumes a default CRS by convention (GeoJSON's implicit EPSG:4326) unless
	 * the sampled coordinates make that impossible, in which case a guess replaces it.
	 */
	static Detection assumed(int assumedSrid, Envelope sampleBbox) {
		if (isPlausible(assumedSrid, sampleBbox)) {
			return new Detection(assumedSrid, CrsConfidence.ASSUMED);
		}
		return new Detection(guessSrid(sampleBbox), CrsConfidence.GUESSED);
	}

	/** The format carries no CRS information at all (CSV, or a Shapefile without a .prj). */
	static Detection guess(Envelope sampleBbox) {
		return new Detection(guessSrid(sampleBbox), CrsConfidence.GUESSED);
	}

	static boolean isPlausible(int srid, Envelope bbox) {
		if (bbox == null || bbox.isNull()) {
			return true; // nothing sampled -- cannot disprove the assumption
		}
		return switch (srid) {
			case 4326 -> withinRange(bbox.getMinX(), bbox.getMaxX(), -180, 180)
					&& withinRange(bbox.getMinY(), bbox.getMaxY(), -90, 90);
			case 25832, 25833 -> withinRange(bbox.getMinX(), bbox.getMaxX(), UTM_EASTING_MIN, UTM_EASTING_MAX)
					&& withinRange(bbox.getMinY(), bbox.getMaxY(), UTM_NORTHING_MIN, UTM_NORTHING_MAX);
			default -> true; // no known validity range to check against
		};
	}

	/**
	 * Guesses a CRS purely from coordinate magnitude. Plain geographic degrees mean
	 * EPSG:4326. An easting/northing pair in the UTM range means one of the two zones
	 * covering Germany; a raw 6-digit easting cannot tell them apart (zones 32 and 33
	 * overlap in easting), but an 8-digit easting with the zone number prefixed -- a common
	 * way German data disambiguates UTM zones -- can. Without a prefix, zone 32 is the
	 * better default: it covers most of the country.
	 */
	static int guessSrid(Envelope bbox) {
		if (bbox == null || bbox.isNull()) {
			return 4326;
		}
		double minX = bbox.getMinX();
		double maxX = bbox.getMaxX();
		double minY = bbox.getMinY();
		double maxY = bbox.getMaxY();

		if (withinRange(minX, maxX, -180, 180) && withinRange(minY, maxY, -90, 90)) {
			return 4326;
		}
		if (isZonePrefixed(minX, maxX, 32) && withinRange(minY, maxY, UTM_NORTHING_MIN, UTM_NORTHING_MAX)) {
			return 25832;
		}
		if (isZonePrefixed(minX, maxX, 33) && withinRange(minY, maxY, UTM_NORTHING_MIN, UTM_NORTHING_MAX)) {
			return 25833;
		}
		if (withinRange(minX, maxX, UTM_EASTING_MIN, UTM_EASTING_MAX)
				&& withinRange(minY, maxY, UTM_NORTHING_MIN, UTM_NORTHING_MAX)) {
			return 25832;
		}
		return 4326;
	}

	private static boolean isZonePrefixed(double min, double max, int zone) {
		double lower = zone * 1_000_000.0;
		double upper = (zone + 1) * 1_000_000.0;
		return withinRange(min, max, lower, upper);
	}

	private static boolean withinRange(double min, double max, double lower, double upper) {
		return min >= lower && max <= upper;
	}
}
