package de.kreuter.hgis.places;

/**
 * One row as {@link PlaceGmlReader} extracts it from Hamburg's WFS, before it becomes a
 * database row. Geometry stays in the source CRS (EPSG:25832, {@code x}/{@code y} meaning
 * easting/northing) -- CONTRACT.md is explicit that the 25832 -&gt; 4326 reprojection
 * happens in PostGIS via {@code ST_Transform}, the same rule {@code ingest.FeatureWriter}
 * follows, never in Java.
 *
 * @param kind {@code "street"} or {@code "district"} -- {@code "place"} is Photon-only and
 *             never written here (V10__place.sql's {@code place_source} check would refuse
 *             it anyway, since only {@code "hamburg"} rows are ever stored)
 */
record ParsedPlace(String name, String context, String kind, double x25832, double y25832) {
}
