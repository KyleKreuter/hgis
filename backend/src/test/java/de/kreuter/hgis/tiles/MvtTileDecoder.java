package de.kreuter.hgis.tiles;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, read-only decoder for the slice of the Mapbox Vector Tile protobuf schema
 * the tests care about: layer name, feature ids and feature properties. No MVT/protobuf
 * library is a project dependency -- ST_AsMVT produces the bytes entirely inside PostGIS,
 * so production code never needs one -- and pulling one in just for a test is not worth
 * a pom.xml change. The wire format is simple enough to walk by hand.
 *
 * Properties are stored per layer, not per feature: a layer carries one table of keys and
 * one of values, and a feature only holds pairs of indices into them. That is what makes
 * a tile small when a thousand features share a handful of category names, and it is why
 * the tables have to be read before a feature's tags mean anything -- hence the two-pass
 * decoding below.
 *
 * Schema reference: https://github.com/mapbox/vector-tile-spec/blob/master/2.1/vector_tile.proto
 */
public final class MvtTileDecoder {

	/**
	 * @param rings the feature's geometry, one entry per ring or line part, each a
	 *              sequence of tile-local points (0..extent, usually 0..4096); empty for
	 *              a feature with no geometry field. Not closed explicitly -- a ring's
	 *              last point connects back to its first, per the MVT ClosePath command,
	 *              which carries no coordinates of its own to decode.
	 */
	public record Feature(long id, Map<String, Object> properties, List<List<long[]>> rings) {

		/** Total vertex count across every ring -- the "Stützpunktzahl" CONTRACT.md phase 19 asks tests to compare. */
		public int pointCount() {
			return rings.stream().mapToInt(List::size).sum();
		}

		/**
		 * The geometry's area in tile-local units, via the shoelace formula summed over
		 * every ring: exterior rings add, holes -- opposite winding order -- subtract.
		 * What CONTRACT.md phase 19 calls the "Fläche" a clip test should compare.
		 */
		public double area() {
			double total = 0;
			for (List<long[]> ring : rings) {
				total += signedArea(ring);
			}
			return Math.abs(total) / 2.0;
		}

		private static double signedArea(List<long[]> ring) {
			double sum = 0;
			int n = ring.size();
			for (int i = 0; i < n; i++) {
				long[] p1 = ring.get(i);
				long[] p2 = ring.get((i + 1) % n);
				sum += (double) p1[0] * p2[1] - (double) p2[0] * p1[1];
			}
			return sum;
		}
	}

	public record Layer(String name, List<String> keys, List<Feature> features) {

		public List<Long> featureIds() {
			return features.stream().map(Feature::id).toList();
		}
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
		List<RawFeature> rawFeatures = new ArrayList<>();
		List<String> keys = new ArrayList<>();
		List<Object> values = new ArrayList<>();

		Cursor c = new Cursor(bytes);
		while (c.hasRemaining()) {
			long tag = c.readVarint();
			int field = (int) (tag >>> 3);
			int wireType = (int) (tag & 0x7);
			switch (field) {
				case 1 -> name = new String(c.readBytes(), StandardCharsets.UTF_8); // Layer.name
				case 2 -> rawFeatures.add(decodeFeature(c.readBytes()));            // Layer.features
				case 3 -> keys.add(new String(c.readBytes(), StandardCharsets.UTF_8)); // Layer.keys
				case 4 -> values.add(decodeValue(c.readBytes()));                   // Layer.values
				default -> c.skip(wireType);
			}
		}

		List<Feature> features = rawFeatures.stream()
				.map(raw -> new Feature(raw.id(), properties(raw.tags(), keys, values), decodeGeometry(raw.geometry())))
				.toList();
		return new Layer(name, keys, features);
	}

	private record RawFeature(long id, List<Integer> tags, List<Long> geometry) {
	}

	private static RawFeature decodeFeature(byte[] bytes) {
		long id = 0;
		List<Integer> tags = new ArrayList<>();
		List<Long> geometry = new ArrayList<>();
		Cursor c = new Cursor(bytes);
		while (c.hasRemaining()) {
			long tag = c.readVarint();
			int field = (int) (tag >>> 3);
			int wireType = (int) (tag & 0x7);
			if (field == 1 && wireType == 0) { // Feature.id
				id = c.readVarint();
			} else if (field == 2 && wireType == 2) { // Feature.tags, packed
				Cursor packed = new Cursor(c.readBytes());
				while (packed.hasRemaining()) {
					tags.add((int) packed.readVarint());
				}
			} else if (field == 4 && wireType == 2) { // Feature.geometry, packed
				Cursor packed = new Cursor(c.readBytes());
				while (packed.hasRemaining()) {
					geometry.add(packed.readVarint());
				}
			} else {
				c.skip(wireType);
			}
		}
		return new RawFeature(id, tags, geometry);
	}

	/**
	 * Turns the packed command/parameter stream of {@code Feature.geometry} into rings
	 * of tile-local points. Commands: 1 = MoveTo (starts a new ring), 2 = LineTo (adds a
	 * point to the current ring), 7 = ClosePath (no parameters). Coordinates are
	 * zigzag-encoded deltas from the previous point, cumulative across the whole feature.
	 *
	 * <p>Schema reference: https://github.com/mapbox/vector-tile-spec/blob/master/2.1/vector_tile.proto#L112
	 */
	private static List<List<long[]>> decodeGeometry(List<Long> commands) {
		List<List<long[]>> rings = new ArrayList<>();
		List<long[]> current = null;
		long x = 0;
		long y = 0;
		int i = 0;
		while (i < commands.size()) {
			long commandInteger = commands.get(i++);
			int id = (int) (commandInteger & 0x7);
			int count = (int) (commandInteger >>> 3);
			if (id == 1 || id == 2) { // MoveTo or LineTo
				for (int p = 0; p < count; p++) {
					x += zigzag(commands.get(i++));
					y += zigzag(commands.get(i++));
					if (id == 1) {
						current = new ArrayList<>();
						rings.add(current);
					}
					current.add(new long[] { x, y });
				}
			}
			// ClosePath (id == 7) carries no parameters and needs no action here: a
			// ring's last point is treated as connected back to its first wherever this
			// decoder measures area or counts points.
		}
		return rings;
	}

	private static long zigzag(long value) {
		return (value >>> 1) ^ -(value & 1);
	}

	/** Value is a one-of; whichever member is present carries the value. */
	private static Object decodeValue(byte[] bytes) {
		Cursor c = new Cursor(bytes);
		while (c.hasRemaining()) {
			long tag = c.readVarint();
			int field = (int) (tag >>> 3);
			int wireType = (int) (tag & 0x7);
			switch (field) {
				case 1 -> {
					return new String(c.readBytes(), StandardCharsets.UTF_8);
				}
				case 2 -> {
					return Float.intBitsToFloat((int) c.readFixed32());
				}
				case 3 -> {
					return Double.longBitsToDouble(c.readFixed64());
				}
				case 4, 5 -> {
					return c.readVarint();
				}
				case 6 -> {
					long zigzag = c.readVarint();
					return (zigzag >>> 1) ^ -(zigzag & 1);
				}
				case 7 -> {
					return c.readVarint() != 0;
				}
				default -> c.skip(wireType);
			}
		}
		return null;
	}

	private static Map<String, Object> properties(List<Integer> tags, List<String> keys,
			List<Object> values) {
		Map<String, Object> properties = new LinkedHashMap<>();
		for (int i = 0; i + 1 < tags.size(); i += 2) {
			properties.put(keys.get(tags.get(i)), values.get(tags.get(i + 1)));
		}
		return properties;
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

		long readFixed32() {
			long result = 0;
			for (int i = 0; i < 4; i++) {
				result |= (long) (data[pos + i] & 0xFF) << (8 * i);
			}
			pos += 4;
			return result;
		}

		long readFixed64() {
			long result = 0;
			for (int i = 0; i < 8; i++) {
				result |= (long) (data[pos + i] & 0xFF) << (8 * i);
			}
			pos += 8;
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
