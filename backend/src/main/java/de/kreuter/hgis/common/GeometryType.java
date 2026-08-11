package de.kreuter.hgis.common;

/**
 * Geometry type of a layer's payload table.
 *
 * Always a multi variant, or {@code GEOMETRY} for a genuinely mixed source -- single
 * geometries are promoted to their multi form on write (both on import and on a hand
 * drawn edit), so a stray point in a line file, or one polygon among many, never fails
 * the whole operation.
 *
 * Lives in {@code common} rather than {@code ingest}, even though it started out nested
 * in {@link de.kreuter.hgis.ingest.spi.SourceSchema}: the catalog now creates layers of
 * its own too, for a user to draw straight into, and {@code catalog} must not depend on
 * {@code ingest} to name their geometry type -- that dependency already runs the other
 * way round.
 */
public enum GeometryType {
	MULTIPOINT, MULTILINESTRING, MULTIPOLYGON, GEOMETRY
}
