package de.kreuter.hgis.glyphs;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.common.ProblemDetailAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test — no database. Glyphs are classpath resources; the only wiring this
 * needs is the controller, the service and the shared exception advice.
 */
@WebMvcTest(controllers = GlyphController.class)
@Import({ GlyphService.class, ProblemDetailAdvice.class })
class GlyphControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void servesBundledGlyphsWithLongLivedCache() throws Exception {
		mockMvc.perform(get("/api/glyphs/{fontstack}/{range}.pbf",
						GlyphService.NOTO_SANS_REGULAR, "0-255"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL,
						"public, max-age=31536000, immutable"))
				.andExpect(content().contentType("application/x-protobuf"));
	}

	@Test
	void returns404ForUnknownFont() throws Exception {
		mockMvc.perform(get("/api/glyphs/{fontstack}/{range}.pbf", "Missing Face", "0-255"))
				.andExpect(status().isNotFound());
	}

	@Test
	void returns400ForMalformedRange() throws Exception {
		mockMvc.perform(get("/api/glyphs/{fontstack}/{range}.pbf",
						GlyphService.NOTO_SANS_REGULAR, "not-a-range"))
				.andExpect(status().isBadRequest());
	}
}
