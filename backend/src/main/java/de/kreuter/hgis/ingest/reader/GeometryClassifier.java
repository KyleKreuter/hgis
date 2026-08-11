package de.kreuter.hgis.ingest.reader;

import de.kreuter.hgis.common.GeometryType;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/**
 * Maps a format's declared geometry binding to the schema's geometry type, for the
 * formats specific enough to declare one at all (Shapefile always, GeoPackage usually).
 *
 * Returns null for the generic {@code Geometry} or {@code GeometryCollection} binding --
 * GeoJSON's rule, and occasionally a loosely typed GeoPackage table -- which tells the
 * caller to fall back to sampling the first features instead.
 */
final class GeometryClassifier {

	private GeometryClassifier() {
	}

	static GeometryType classify(Class<?> binding) {
		if (binding == null) {
			return null;
		}
		if (Point.class.isAssignableFrom(binding) || MultiPoint.class.isAssignableFrom(binding)) {
			return GeometryType.MULTIPOINT;
		}
		if (LineString.class.isAssignableFrom(binding) || MultiLineString.class.isAssignableFrom(binding)) {
			return GeometryType.MULTILINESTRING;
		}
		if (Polygon.class.isAssignableFrom(binding) || MultiPolygon.class.isAssignableFrom(binding)) {
			return GeometryType.MULTIPOLYGON;
		}
		return null;
	}
}
