package de.kreuter.hgis.catalog;

import de.kreuter.hgis.common.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Position in the project browser's paging order: {@code lastOpenedAt} (null for a
 * project that was never opened), {@code createdAt} and {@code id} -- exactly the three
 * columns {@link ProjectRepository#findPage} sorts and filters by, in that order. All
 * three travel together because breaking a tie needs all three; see
 * {@code CONTRACT.md} phase 22 for why {@code id} cannot be dropped.
 *
 * <p>Encoded as base64 JSON and treated as opaque by the client, the same way
 * {@link de.kreuter.hgis.features.FeatureCursor} already is for a layer's rows.
 */
record ProjectCursor(Instant lastOpenedAt, Instant createdAt, UUID id) {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	String encode() {
		ObjectNode node = MAPPER.createObjectNode();
		// Instant.toString(), not toEpochMilli(): PostgreSQL stores and compares
		// timestamptz at microsecond precision, and Instant itself carries nanoseconds.
		// Rounding to milliseconds here would make the cursor coarser than the column it
		// has to compare against -- a row sharing the anchor's millisecond but differing
		// only in its microseconds would then compare equal to values it is not equal to,
		// and fall out of the very next page. ISO-8601 round-trips through Instant.parse
		// without losing that precision.
		if (lastOpenedAt == null) {
			node.putNull("o");
		}
		else {
			node.put("o", lastOpenedAt.toString());
		}
		node.put("c", createdAt.toString());
		node.put("i", id.toString());
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(MAPPER.writeValueAsBytes(node));
	}

	static ProjectCursor decode(String encoded) {
		try {
			byte[] json = Base64.getUrlDecoder().decode(encoded);
			JsonNode node = MAPPER.readTree(new String(json, StandardCharsets.UTF_8));
			JsonNode opened = node.get("o");

			Instant lastOpenedAt = (opened == null || opened.isNull())
					? null
					: Instant.parse(opened.asString());
			Instant createdAt = Instant.parse(node.get("c").asString());
			UUID id = UUID.fromString(node.get("i").asString());

			return new ProjectCursor(lastOpenedAt, createdAt, id);
		}
		catch (IllegalArgumentException | DateTimeException | JacksonException | NullPointerException ex) {
			// A cursor is only ever produced by this class, so a broken one means it was
			// tampered with or a stale link was followed -- both are the caller's problem.
			// DateTimeException (Instant.parse) is its own hierarchy, not an
			// IllegalArgumentException, and needs naming here explicitly.
			throw new BadRequestException("Ungültiger Cursor");
		}
	}
}
