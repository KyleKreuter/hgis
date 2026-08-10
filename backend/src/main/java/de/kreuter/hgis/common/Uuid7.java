package de.kreuter.hgis.common;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Generates UUIDv7 values outside of a JPA persist cycle.
 *
 * {@code @UuidGenerator(style = VERSION_7)} covers entities such as {@code Project},
 * where Hibernate assigns the id during flush. That does not work for a layer: the
 * table name has to be derived from the id before the row is ever inserted (the table
 * must exist first), so the id has to be known up front. This produces the same kind
 * of value by hand -- a 48 bit millisecond timestamp in the high bits followed by
 * random bits -- so ids keep the chronological locality described on {@code Project}.
 */
public final class Uuid7 {

	private static final SecureRandom RANDOM = new SecureRandom();

	private Uuid7() {
	}

	public static UUID generate() {
		long millis = Instant.now().toEpochMilli() & 0xFFFF_FFFF_FFFFL; // 48 bits

		long randA = RANDOM.nextLong() & 0xFFFL; // 12 random bits
		long msb = (millis << 16) | (0x7L << 12) | randA; // version nibble = 0111

		long randB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL; // 62 random bits
		long lsb = (0b10L << 62) | randB; // variant bits = 10

		return new UUID(msb, lsb);
	}
}
