package de.kreuter.hgis.ingest.reader;

import de.kreuter.hgis.ingest.spi.SourceSchema.GeometryType;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Set;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/**
 * Samples the first {@code limit} geometries of a source to answer two questions a format
 * without a declared schema leaves open: what geometry family the data actually is, and
 * whether a candidate CRS is even plausible for the coordinate magnitudes present.
 *
 * One pass answers both, which matters for formats without an index -- a GeoJSON file or a
 * CSV is read front to back regardless of which question is being asked.
 */
final class FeatureSampling {

	record Sample(GeometryType geometryType, Envelope bbox) {
	}

	private enum Family {
		POINT, LINE, POLYGON
	}

	private FeatureSampling() {
	}

	static Sample sample(Iterator<Geometry> geometries, int limit) {
		Set<Family> families = EnumSet.noneOf(Family.class);
		Envelope bbox = new Envelope();
		int count = 0;
		while (geometries.hasNext() && count < limit) {
			Geometry geometry = geometries.next();
			count++;
			if (geometry == null || geometry.isEmpty()) {
				continue;
			}
			bbox.expandToInclude(geometry.getEnvelopeInternal());
			Family family = familyOf(geometry);
			if (family != null) {
				families.add(family);
			}
		}
		return new Sample(toGeometryType(families), bbox);
	}

	private static Family familyOf(Geometry geometry) {
		if (geometry instanceof Point || geometry instanceof MultiPoint) {
			return Family.POINT;
		}
		if (geometry instanceof LineString || geometry instanceof MultiLineString) {
			return Family.LINE;
		}
		if (geometry instanceof Polygon || geometry instanceof MultiPolygon) {
			return Family.POLYGON;
		}
		return null; // GeometryCollection or something exotic: forces GEOMETRY below
	}

	private static GeometryType toGeometryType(Set<Family> families) {
		if (families.size() != 1) {
			return GeometryType.GEOMETRY;
		}
		return switch (families.iterator().next()) {
			case POINT -> GeometryType.MULTIPOINT;
			case LINE -> GeometryType.MULTILINESTRING;
			case POLYGON -> GeometryType.MULTIPOLYGON;
		};
	}
}
