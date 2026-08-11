package de.kreuter.hgis.glyphs;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Serves signed-distance-field glyph PBFs for MapLibre label layers.
 *
 * The files live under {@code classpath:glyphs/<fontstack>/<range>.pbf}. Only the
 * font stacks we actually ship are accepted -- anything else is a 404, and a
 * path that looks like traversal is a 400. Ranges we have not bundled (CJK, …)
 * are likewise 404; MapLibre then skips those codepoints rather than falling
 * back to a remote glyph host.
 */
@Service
public class GlyphService {

	/** Must match {@code LABEL_FONT} in the frontend. */
	public static final String NOTO_SANS_REGULAR = "Noto Sans Regular";

	private static final Set<String> KNOWN_FONTS = Set.of(NOTO_SANS_REGULAR);

	/** MapLibre asks for blocks of 256 codepoints, e.g. {@code 0-255}. */
	private static final Pattern RANGE = Pattern.compile("^\\d+-\\d+$");

	public byte[] load(String fontstack, String range) {
		String stack = requireKnownFont(fontstack);
		String block = requireValidRange(range);

		ClassPathResource resource = new ClassPathResource("glyphs/" + stack + "/" + block + ".pbf");
		if (!resource.exists()) {
			throw new NotFoundException(
					"Glyphen " + stack + "/" + block + " sind nicht vorhanden");
		}
		try (InputStream in = resource.getInputStream()) {
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new IllegalStateException("Glyphen " + stack + "/" + block + " konnten nicht gelesen werden", ex);
		}
	}

	private static String requireKnownFont(String fontstack) {
		if (fontstack == null || fontstack.isBlank()) {
			throw new BadRequestException("fontstack fehlt");
		}
		// MapLibre may request a comma-separated stack; we only ship one face.
		String primary = fontstack.split(",", 2)[0].trim();
		if (primary.contains("..") || primary.indexOf('/') >= 0 || primary.indexOf('\\') >= 0) {
			throw new BadRequestException("fontstack ist ungültig");
		}
		if (!KNOWN_FONTS.contains(primary)) {
			throw new NotFoundException("Schriftart \"" + primary + "\" ist nicht vorhanden");
		}
		return primary;
	}

	private static String requireValidRange(String range) {
		if (range == null || !RANGE.matcher(range).matches()) {
			throw new BadRequestException("range muss die Form <start>-<end> haben, war \"" + range + "\"");
		}
		return range;
	}
}
