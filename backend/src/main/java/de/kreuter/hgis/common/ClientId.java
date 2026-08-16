package de.kreuter.hgis.common;

import java.util.regex.Pattern;

/**
 * The {@code X-Hgis-Client} header: an opaque name a client gives itself, so it can
 * recognise its own change when the live channel reports it back (plan "Live-Kanal").
 *
 * <p>Never interpreted, never stored, never compared against anything the server knows --
 * it is only echoed to the other clients, which is exactly why it is checked here.
 * Restricting it to letters, digits, hyphen and underscore means whatever a client picks
 * stays harmless in the places it ends up: an SSE data line, and whatever a receiver
 * chooses to do with it.
 */
public final class ClientId {

	/** Header carrying the value; the same name the frontend and the Python library use. */
	public static final String HEADER = "X-Hgis-Client";

	/** Long enough for a UUID with hyphens, short enough to stay a name rather than a payload. */
	private static final int MAX_LENGTH = 64;

	private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-]{1,%d}".formatted(MAX_LENGTH));

	private ClientId() {
	}

	/**
	 * @param value the raw header, or null when it was not sent
	 * @return the value, or null when no client named itself -- an absent name is normal,
	 *     not an error: such a client simply hears its own change like anyone else's
	 * @throws BadRequestException when a name was given but cannot be echoed as-is. A
	 *     malformed one is a mistake in the calling program, and answering it silently
	 *     would leave that program wondering why it keeps hearing itself.
	 */
	public static String require(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.trim();
		if (!ALLOWED.matcher(trimmed).matches()) {
			throw new BadRequestException(HEADER + " darf höchstens " + MAX_LENGTH
					+ " Zeichen lang sein und nur Buchstaben, Ziffern, - und _ enthalten");
		}
		return trimmed;
	}
}
