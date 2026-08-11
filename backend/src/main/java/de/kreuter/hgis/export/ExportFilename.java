package de.kreuter.hgis.export;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Builds the {@code Content-Disposition} header of an export download.
 *
 * <p>A layer name is free text. It may hold umlauts, spaces, a double quote, a semicolon
 * or a CRLF pair, and every one of those means something inside a header value -- the
 * last one means "the header ends here, what follows is the next one". Nothing from the
 * name therefore reaches the header as it stands.
 *
 * <p>Both forms of the parameter are emitted, as RFC 6266 recommends. The plain
 * {@code filename} is reduced to a strict ASCII character class so it cannot carry a
 * delimiter, and the readable original travels percent-encoded in the RFC 5987
 * {@code filename*}, which every current browser prefers when both are present.
 */
public final class ExportFilename {

	/** Everything outside this class collapses into an underscore. */
	private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]+");

	private static final Pattern REPEATED_UNDERSCORE = Pattern.compile("_{2,}");

	/** Percent-encoding leaves these alone; RFC 3986 calls them unreserved. */
	private static final String UNRESERVED =
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	/** Long enough to stay recognisable, short enough for any filesystem. */
	private static final int MAX_STEM_LENGTH = 80;

	private static final String FALLBACK_STEM = "layer";

	private ExportFilename() {
	}

	/**
	 * @param layerName the name as the UI shows it
	 * @param extension without the dot, e.g. {@code geojson}
	 */
	public static String contentDisposition(String layerName, String extension) {
		return "attachment; filename=\"" + asciiFilename(layerName, extension) + "\""
				+ "; filename*=UTF-8''" + percentEncode(readableFilename(layerName, extension));
	}

	/** The conservative fallback: only letters, digits, dot, underscore and hyphen. */
	static String asciiFilename(String layerName, String extension) {
		String stem = transliterate(layerName == null ? "" : layerName);
		stem = UNSAFE.matcher(stem).replaceAll("_");
		stem = REPEATED_UNDERSCORE.matcher(stem).replaceAll("_");
		stem = trim(stem);

		if (stem.length() > MAX_STEM_LENGTH) {
			stem = trim(stem.substring(0, MAX_STEM_LENGTH));
		}
		if (stem.isEmpty()) {
			stem = FALLBACK_STEM;
		}
		return stem + "." + extension;
	}

	/**
	 * The name a browser actually saves under. Kept as written apart from control
	 * characters, which no filesystem accepts and no header should carry even encoded.
	 */
	static String readableFilename(String layerName, String extension) {
		StringBuilder stem = new StringBuilder();
		for (char character : (layerName == null ? "" : layerName).toCharArray()) {
			// Path separators would turn a download into a write outside its directory
			// on a client that trusts the name; underscore is the same substitution the
			// ASCII form makes.
			if (character == '/' || character == '\\') {
				stem.append('_');
			}
			else if (!Character.isISOControl(character)) {
				stem.append(character);
			}
			if (stem.length() >= MAX_STEM_LENGTH) {
				break;
			}
		}

		String trimmed = stem.toString().strip();
		return (trimmed.isEmpty() ? FALLBACK_STEM : trimmed) + "." + extension;
	}

	/**
	 * RFC 5987 percent-encoding of the UTF-8 bytes. Not {@code URLEncoder}: that is form
	 * encoding, which writes a space as {@code +} -- a literal plus in a filename.
	 */
	static String percentEncode(String value) {
		StringBuilder encoded = new StringBuilder(value.length() * 2);
		for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
			char character = (char) (raw & 0xFF);
			if (UNRESERVED.indexOf(character) >= 0) {
				encoded.append(character);
			}
			else {
				encoded.append('%').append(HEX[(raw >> 4) & 0xF]).append(HEX[raw & 0xF]);
			}
		}
		return encoded.toString();
	}

	/** Same rules as {@code SqlIdentifier}: German umlauts spelled out, other marks dropped. */
	private static String transliterate(String value) {
		String replaced = value
				.replace("ä", "ae").replace("Ä", "Ae")
				.replace("ö", "oe").replace("Ö", "Oe")
				.replace("ü", "ue").replace("Ü", "Ue")
				.replace("ß", "ss");
		return Normalizer.normalize(replaced, Normalizer.Form.NFD)
				.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
	}

	private static String trim(String value) {
		int start = 0;
		int end = value.length();
		while (start < end && isPadding(value.charAt(start))) {
			start++;
		}
		while (end > start && isPadding(value.charAt(end - 1))) {
			end--;
		}
		return value.substring(start, end);
	}

	/** A leading dot would make the file hidden; leading or trailing separators read as noise. */
	private static boolean isPadding(char character) {
		return character == '_' || character == '.' || character == '-';
	}
}
