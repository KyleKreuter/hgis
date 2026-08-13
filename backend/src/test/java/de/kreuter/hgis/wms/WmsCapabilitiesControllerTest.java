package de.kreuter.hgis.wms;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.ProblemDetailAdvice;
import de.kreuter.hgis.common.UnprocessableEntityException;
import de.kreuter.hgis.wms.dto.WmsDtos;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CONTRACT.md 2, with {@link WmsCapabilitiesService} mocked: proves the routing and the
 * error-status mapping table the contract spells out -- 400 for a refused address, 422
 * for a version or CRS problem, 502 for a service that never answered. The service's own
 * behaviour is {@link WmsCapabilitiesParserTest}, {@link WmsUrlGuardTest} and
 * {@link WmsCapabilitiesFetcherTest}'s job.
 */
@WebMvcTest(controllers = WmsCapabilitiesController.class)
@Import({ ProblemDetailAdvice.class, WmsOutageAdvice.class })
class WmsCapabilitiesControllerTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private WmsCapabilitiesService service;

	@Test
	@DisplayName("a successful capabilities call returns 200 with the parsed shape")
	void returnsTheParsedCapabilities() throws Exception {
		WmsDtos.Layer layer = new WmsDtos.Layer("stadtplan", "Stadtplan", 0, false, null, null, null, null);
		given(service.capabilities("https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan")).willReturn(
				new WmsDtos.CapabilitiesResponse("https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan",
						"WMS Cache Stadtplan Hamburg", "1.3.0", List.of("image/png"), List.of(layer)));

		mvc.perform(get("/api/wms/capabilities").param("url", "https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("WMS Cache Stadtplan Hamburg"))
				.andExpect(jsonPath("$.layers[0].name").value("stadtplan"));
	}

	@Test
	@DisplayName("a refused address (CONTRACT.md: SSRF guard) is a 400")
	void aRefusedAddressIsBadRequest() throws Exception {
		given(service.capabilities("http://127.0.0.1"))
				.willThrow(new BadRequestException("Diese Adresse ist nicht erlaubt."));

		mvc.perform(get("/api/wms/capabilities").param("url", "http://127.0.0.1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Diese Adresse ist nicht erlaubt."));
	}

	@Test
	@DisplayName("a service without EPSG:3857 is a 422")
	void aServiceWithoutWebMercatorIsUnprocessable() throws Exception {
		given(service.capabilities("https://geodienste.hamburg.de/HH_WMS_Cache_Rasterplan")).willThrow(
				new UnprocessableEntityException("Dieser Dienst liefert keine Karten in Web-Mercator (EPSG:3857). "
						+ "hGIS kann ihn nicht anzeigen."));

		mvc.perform(get("/api/wms/capabilities").param("url", "https://geodienste.hamburg.de/HH_WMS_Cache_Rasterplan"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("EPSG:3857")));
	}

	@Test
	@DisplayName("an unreachable service is a 502 with Retry-After")
	void anUnreachableServiceIsBadGateway() throws Exception {
		given(service.capabilities("https://example.test/wms"))
				.willThrow(new WmsUnavailableException("Der Dienst hat nicht geantwortet."));

		mvc.perform(get("/api/wms/capabilities").param("url", "https://example.test/wms"))
				.andExpect(status().isBadGateway())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
						.exists("Retry-After"));
	}

	@Test
	@DisplayName("a missing url parameter is a 400")
	void aMissingUrlParameterIsBadRequest() throws Exception {
		mvc.perform(get("/api/wms/capabilities"))
				.andExpect(status().isBadRequest());
	}
}
