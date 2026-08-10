package de.kreuter.hgis.tiles;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal, read-only decoder for the slice of the Mapbox Vector Tile protobuf schema
 * the tests care about: layer name and feature ids. No MVT/protobuf library is a
 * project dependency -- ST_AsMVT produces the bytes entirely inside PostGIS, so
 * production code never needs one -- and pulling one in just for a test is not worth
 * a pom.xml change. The wire format is simple enough to walk by hand.
 *
 * Schema reference: https://github.com/mapbox/vector-tile-spec/blob/master/2.1/vector_tile.proto
 */
public final class MvtTileDecoder {

	public record Layer(String name, List<Long> featureIds) {
	}

	private MvtTileDecoder() {
	}

	public static List<Layer> decode(byte[] mvt) {
		List<Layer> layers = new ArrayList<>();
		Cursor tile = new Cursor(mvt);
		while (tile.hasRemaining()) {
			long tag = tile.readVarint();
			int field = (int) (tag >>> 3);
			int wireType = (int) (tag & 0x7);
			if (field == 3 && wireType == 2) { // Tile.layers
				layers.add(decodeLayer(tile.readBytes()));
			} else {
				tile.skip(wireType);
			}
		}
		return layers;
	}

	private static Layer decodeLayer(byte[] bytes) {
		String name = null;
		List<Long> featureIds = new ArrayList<>();
		Cursor c = new Cursor(bytes);
		while (c.hasRemaining()) {
			long tag = c.readVarint();
			int field = (int) (tag >>> 3);
			int wireType = (int) (tag & 0x7);
			if (field == 1 && wireType == 2) { // Layer.name
				name = new String(c.readBytes(), StandardCharsets.UTF_8);
			} else if (field == 2 && wireType == 2) { // Layer.features
				featureIds.add(decodeFeatureId(c.readBytes()));
			} else {
				c.skip(wireType);
			}
		}
		return new Layer(name, featureIds);
	}

	private static long decodeFeatureId(byte[] bytes) {
		long id = 0;
		Cursor c = new Cursor(bytes);
		while (c.hasRemaining()) {
			long tag = c.readVarint();
			int field = (int) (tag >>> 3);
			int wireType = (int) (tag & 0x7);
			if (field == 1 && wireType == 0) { // Feature.id
				id = c.readVarint();
			} else {
				c.skip(wireType);
			}
		}
		return id;
	}

	/** Sequential cursor over a byte range, tracking position for protobuf wire-format reads. */
	private static final class Cursor {
		private final byte[] data;
		private int pos;

		Cursor(byte[] data) {
			this.data = data;
			this.pos = 0;
		}

		boolean hasRemaining() {
			return pos < data.length;
		}

		long readVarint() {
			long result = 0;
			int shift = 0;
			while (true) {
				byte b = data[pos++];
				result |= (long) (b & 0x7F) << shift;
				if ((b & 0x80) == 0) {
					break;
				}
				shift += 7;
			}
			return result;
		}

		byte[] readBytes() {
			int length = (int) readVarint();
			byte[] slice = Arrays.copyOfRange(data, pos, pos + length);
			pos += length;
			return slice;
		}

		void skip(int wireType) {
			switch (wireType) {
				case 0 -> readVarint();
				case 1 -> pos += 8;
				case 2 -> readBytes();
				case 5 -> pos += 4;
				default -> throw new IllegalStateException("Unbekannter Wire-Type: " + wireType);
			}
		}
	}
}
