package de.kreuter.hgis.basemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** {@code GET /api/basemaps} (VERTRAG.md). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BasemapControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void returnsTheWholeCatalogWrappedInABasemapsKey() throws Exception {
		mockMvc.perform(get("/api/basemaps"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.basemaps").isArray())
				.andExpect(jsonPath("$.basemaps.length()").value(BasemapCatalog.list().size()));
	}

	/** basemap.de Grau -- the exact example VERTRAG.md gives for the wire shape. */
	@Test
	void aDeutschlandEntryHasTheContractShape() throws Exception {
		JsonNode entry = fetchEntry("basemapde-grau");

		assertThat(entry.get("title").asString()).isEqualTo("basemap.de Grau");
		assertThat(entry.get("group").asString()).isEqualTo("Deutschland");
		assertThat(entry.get("coverage").asString()).isEqualTo("DE");
		assertThat(entry.get("requiresAccount").asBoolean()).isFalse();
		assertThat(entry.get("deprecated").asBoolean()).isFalse();
		assertThat(entry.get("paint").isNull()).isTrue();
		assertThat(entry.get("urlTemplate").asString()).contains("{z}", "{x}", "{y}");
	}

	/** An Esri entry is shown, not hidden -- but flagged. */
	@Test
	void anEsriEntryRequiresAnAccount() throws Exception {
		JsonNode entry = fetchEntry("esri-imagery");
		assertThat(entry.get("requiresAccount").asBoolean()).isTrue();
	}

	/** {@code osm-light} carries the raster-paint properties that used to live only in the frontend. */
	@Test
	void osmLightCarriesItsPaintProperties() throws Exception {
		JsonNode entry = fetchEntry("osm-light");
		assertThat(entry.get("paint").get("raster-saturation").asDouble()).isEqualTo(-0.9);
	}

	@Test
	void noneHasNoUrlTemplateAndNoAttribution() throws Exception {
		JsonNode entry = fetchEntry("none");
		assertThat(entry.get("urlTemplate").isNull()).isTrue();
		assertThat(entry.get("attribution").isEmpty()).isTrue();
	}

	/**
	 * Befund (27.08.): the first VERTRAG.md revision itself named {@code "Gelaende"} and
	 * {@code "Bundeslaender"} without their umlaut; {@link BasemapCatalog}'s constants
	 * briefly matched that literal. Corrected in both places once the team lead caught it
	 * -- this test reads the raw response bytes as UTF-8 rather than trusting the source
	 * file's own encoding, so it would have failed on the ASCII value too, not just
	 * looked right in the Java source.
	 */
	@Test
	void groupNamesCarryTheirRealUmlautsOverTheWire() throws Exception {
		byte[] raw = mockMvc.perform(get("/api/basemaps"))
				.andReturn().getResponse().getContentAsByteArray();
		String body = new String(raw, StandardCharsets.UTF_8);
		JsonNode basemaps = objectMapper.readTree(body).get("basemaps");

		JsonNode opentopo = null;
		for (JsonNode candidate : basemaps) {
			if (candidate.get("id").asString().equals("opentopo")) {
				opentopo = candidate;
			}
		}
		assertThat(opentopo).as("opentopo entry").isNotNull();
		assertThat(opentopo.get("group").asString()).isEqualTo("Gelände");

		// The literal UTF-8 bytes of "Gelände" (ä = 0xC3 0xA4), searched for in the raw
		// response bytes -- proves the encoding survived the controller/Jackson/servlet
		// round trip itself, not just the JVM's String comparison after decoding.
		byte[] expected = "Gelände".getBytes(StandardCharsets.UTF_8);
		assertThat(indexOf(raw, expected)).as("UTF-8 bytes of 'Gelände' in the raw response").isNotNegative();
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private JsonNode fetchEntry(String id) throws Exception {
		String body = mockMvc.perform(get("/api/basemaps"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		JsonNode basemaps = objectMapper.readTree(body).get("basemaps");
		Iterator<JsonNode> it = basemaps.iterator();
		while (it.hasNext()) {
			JsonNode candidate = it.next();
			if (candidate.get("id").asString().equals(id)) {
				return candidate;
			}
		}
		throw new AssertionError("no basemap entry " + id + " in response");
	}
}
