package de.kreuter.hgis.features;

import de.kreuter.hgis.common.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
			case Number number -> node.put("v", number.doubleValue());
			case Boolean bool -> node.put("v", bool);
			default -> node.put("v", sortValue.toString());
		}
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(MAPPER.writeValueAsBytes(node));
	}

	static FeatureCursor decode(String encoded) {
		try {
			byte[] json = Base64.getUrlDecoder().decode(encoded);
			JsonNode node = MAPPER.readTree(new String(json, StandardCharsets.UTF_8));
			JsonNode value = node.get("v");

			Object sortValue = (value == null || value.isNull()) ? null
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
