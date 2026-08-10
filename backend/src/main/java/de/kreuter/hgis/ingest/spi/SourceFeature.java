package de.kreuter.hgis.ingest.spi;

import java.util.Map;
import org.locationtech.jts.geom.Geometry;

/**
 * One feature as delivered by a reader.
 *
 * @param geometry   in the schema's sourceSrid, not yet reprojected -- transforming is
 *                   the writing side's job, which does it in PostGIS rather than Java
 * @param attributes keyed by {@link SourceField#name()}, values of the declared type or
 *                   null. Missing keys count as null.
 */
public record SourceFeature(Geometry geometry, Map<String, Object> attributes) {
}
