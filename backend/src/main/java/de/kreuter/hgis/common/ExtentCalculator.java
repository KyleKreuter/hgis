package de.kreuter.hgis.common;

import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Bounding box of a layer's payload table, as a polygon in EPSG:4326.
 *
 * <p>Shared by the import and the editor, because both change what a layer covers and
 * both have to leave {@code layer.extent} correct afterwards -- the map reads it to pick
 * its opening view and the layer tree to zoom.
 *
 * <p>Transported as WKB rather than WKT: text would round the coordinates on the way out
 * and again on the way in, and an extent that drifts is worse than useless for a
 * fit-to-bounds.
 */
@Component
public class ExtentCalculator {

	private final JdbcClient jdbc;
	private final GeometryFactory wgs84GeometryFactory;

	ExtentCalculator(JdbcClient jdbc, GeometryFactory wgs84GeometryFactory) {
		this.jdbc = jdbc;
		this.wgs84GeometryFactory = wgs84GeometryFactory;
	}

	/** @return the extent, or null for an empty table */
	public Polygon forLayer(String tableName, int srid) {
		byte[] wkb = jdbc.sql("""
				SELECT ST_AsBinary(ST_Transform(ST_SetSRID(ST_Extent(geom)::geometry, :srid), 4326))
				FROM %s
				""".formatted(SqlIdentifier.quoteLayerTable(tableName)))
				.param("srid", srid)
				.query(byte[].class)
				.optional()
				.orElse(null);

		if (wkb == null) {
			return null;
		}
		try {
			return (Polygon) new WKBReader(wgs84GeometryFactory).read(wkb);
		}
		catch (ParseException ex) {
			throw new IllegalStateException("Von PostGIS gelieferte Extent-Geometrie ist nicht lesbar", ex);
		}
	}
}
