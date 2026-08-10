package de.kreuter.hgis.common;

import java.util.List;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
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
			Geometry extent = new WKBReader(wgs84GeometryFactory).read(wkb);
			return toPolygon(extent.getEnvelopeInternal());
		}
		catch (ParseException ex) {
			throw new IllegalStateException("Von PostGIS gelieferte Extent-Geometrie ist nicht lesbar", ex);
		}
	}

	/**
	 * Union of a project's layer extents, for the view a project opens with.
	 *
	 * <p>Computed here rather than in SQL for the same reason as {@link #forLayer}: a
	 * project holding one point layer has a point for an extent, and neither
	 * {@code ST_Extent} nor {@code ST_Envelope} will turn that into the polygon the column
	 * expects. Reading a handful of layer rows and combining them in Java sidesteps the
	 * whole class of degenerate cases.
	 *
	 * @return the extent, or null when the project has no layer with one
	 */
	public Polygon forProject(UUID projectId) {
		List<byte[]> extents = jdbc.sql("""
				SELECT ST_AsBinary(extent)
				FROM gis_meta.layer
				WHERE project_id = :projectId AND extent IS NOT NULL
				""")
				.param("projectId", projectId)
				.query(byte[].class)
				.list();

		Envelope combined = new Envelope();
		WKBReader reader = new WKBReader(wgs84GeometryFactory);
		for (byte[] wkb : extents) {
			try {
				combined.expandToInclude(reader.read(wkb).getEnvelopeInternal());
			}
			catch (ParseException ex) {
				throw new IllegalStateException("Layer-Extent ist nicht lesbar", ex);
			}
		}
		return toPolygon(combined);
	}

	/**
	 * Builds the rectangle for an envelope, degenerate cases included.
	 *
	 * <p>{@code ST_Extent} returns the smallest geometry that covers the input, and for a
	 * layer holding a single feature -- or several stacked on one coordinate -- that is a
	 * point, not a polygon. Casting the result therefore failed for the most ordinary
	 * layer imaginable: one with exactly one object in it.
	 *
	 * <p>The rectangle is constructed from the envelope instead, so a point yields a
	 * zero-size box. That is the honest answer: the extent of one point is that point, and
	 * the map's fitBounds already caps the zoom for a box without size.
	 */
	private Polygon toPolygon(Envelope envelope) {
		if (envelope.isNull()) {
			return null;
		}
		return wgs84GeometryFactory.createPolygon(new Coordinate[] {
				new Coordinate(envelope.getMinX(), envelope.getMinY()),
				new Coordinate(envelope.getMaxX(), envelope.getMinY()),
				new Coordinate(envelope.getMaxX(), envelope.getMaxY()),
				new Coordinate(envelope.getMinX(), envelope.getMaxY()),
				new Coordinate(envelope.getMinX(), envelope.getMinY()),
		});
	}
}
