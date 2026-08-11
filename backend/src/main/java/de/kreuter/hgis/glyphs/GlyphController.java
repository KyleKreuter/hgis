package de.kreuter.hgis.glyphs;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MapLibre glyph endpoint. URL shape matches the style-spec template
 * {@code …/{fontstack}/{range}.pbf}; the PBFs themselves never change, so the
 * response is cacheable forever.
 */
@RestController
@RequestMapping("/api/glyphs")
public class GlyphController {

	private static final MediaType PBF = MediaType.parseMediaType("application/x-protobuf");

	private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

	private final GlyphService glyphService;

	GlyphController(GlyphService glyphService) {
		this.glyphService = glyphService;
	}

	@GetMapping("/{fontstack}/{range}.pbf")
	public ResponseEntity<byte[]> glyph(
			@PathVariable String fontstack,
			@PathVariable String range) {

		byte[] body = glyphService.load(fontstack, range);
		return ResponseEntity.ok()
				.contentType(PBF)
				.header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
				.body(body);
	}
}
