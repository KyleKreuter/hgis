package de.kreuter.hgis.features;

import de.kreuter.hgis.common.BadRequestException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.Base64;
import java.util.HexFormat;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Position in a sorted result set: the sort column's value plus the {@code fid} of the
 * last row delivered.
 *
 * <p>Both parts are needed. The value alone is not a position because it repeats -- a
 * hundred buildings can share a street name -- and paging on it would skip or duplicate
 * rows. The {@code fid} breaks that tie, which is why the sort always ends with it.
 *
 * <p>Encoded as base64 JSON and treated as opaque by the client. That is not obfuscation:
 * it keeps the wire format free of a structure callers might start to construct by hand,
 * which would freeze this into an interface it was never meant to be.
 */
record FeatureCursor(Object sortValue, long fid) {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	String encode() {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("f", fid);
		switch (sortValue) {
			case null -> node.putNull("v");
			// numeric arrives as a BigDecimal, and it is the one number no JSON number can
			// carry: the column keeps whatever precision it was declared with, while a JSON
			// double holds 15 digits and re-reading one gives a double back regardless of
			// how it was written. So it travels as its own decimal text and is read back in
			// SQL -- the route date, time, uuid and bytea already take.
			case BigDecimal decimal -> node.put("v", decimal.toPlainString());
			case Number number -> putNumber(node, number);
			case Boolean bool -> node.put("v", bool);
			// The driver hands a bytea column back as a byte array, whose toString() is its
			// identity hash -- a cursor built from that points at nothing and pages forever
			// through the same rows. PostgreSQL's own hex form is what the column can be
			// compared against again.
			case byte[] bytes -> node.put("v", "\\x" + HexFormat.of().formatHex(bytes));
			// java.sql.Time prints hours, minutes and seconds and drops the milliseconds it
			// still carries. A cursor rounded down that way lands *before* the row it was
			// taken from, so the next page starts by repeating it -- and never gets past it.
			case java.sql.Time time -> node.put("v", withMillis(time).toString());
			default -> node.put("v", sortValue.toString());
		}
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(MAPPER.writeValueAsBytes(node));
	}

	/**
	 * Writes a sort value that is a number.
	 *
	 * <p>Whole numbers stay whole. A {@code bigint} is 64 bits and a JSON double carries 53
	 * of them, so an id or a population count past 9.007.199.254.740.992 would come back
	 * with its last digits rounded -- and a keyset built on a value that never occurs in the
	 * table steps straight over the rows between it and the real one.
	 */
	private static void putNumber(ObjectNode node, Number number) {
		switch (number) {
			case Byte b -> node.put("v", b.longValue());
			case Short s -> node.put("v", s.longValue());
			case Integer i -> node.put("v", i.longValue());
			case Long l -> node.put("v", l);
			case BigInteger big -> node.put("v", big);
			default -> node.put("v", number.doubleValue());
		}
	}

	/** {@code time} with the sub-second part the driver kept but {@code toString} hides. */
	private static LocalTime withMillis(java.sql.Time time) {
		return time.toLocalTime()
				.withNano((int) (Math.floorMod(time.getTime(), 1000L) * 1_000_000L));
	}

	static FeatureCursor decode(String encoded) {
		try {
			byte[] json = Base64.getUrlDecoder().decode(encoded);
			JsonNode node = MAPPER.readTree(new String(json, StandardCharsets.UTF_8));
			JsonNode value = node.get("v");

			Object sortValue = (value == null || value.isNull()) ? null
					// Read back as a long, mirroring encode(): going through a double here
					// would throw away exactly the bigint digits it took care to keep.
					: value.isIntegralNumber() ? value.asLong()
					: value.isNumber() ? value.doubleValue()
					: value.isBoolean() ? value.booleanValue()
					: value.asString();

			return new FeatureCursor(sortValue, node.get("f").asLong());
		}
		catch (IllegalArgumentException | JacksonException | NullPointerException ex) {
			// A cursor is only ever produced by this class, so a broken one means it was
			// tampered with or a stale link was followed -- both are the caller's problem.
			throw new BadRequestException("Ungültiger Cursor");
		}
	}
}
