package de.kreuter.hgis.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.wms.dto.WmsDtos;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wires fetcher and parser together and proves the one thing neither's own tests cover
 * on its own: that a URL given without {@code ?SERVICE=WMS&REQUEST=GetCapabilities}
 * (CONTRACT.md 2) actually reaches the server with both added, and one already given
 * completely is left untouched. {@link WmsUrlGuard} is mocked -- offline, loopback-only
 * -- the same as {@link WmsCapabilitiesFetcherTest}.
 */
class WmsCapabilitiesServiceTest {

	private HttpServer server;
	private int port;
	private WmsCapabilitiesService service;
	private final AtomicReference<String> requestedQuery = new AtomicReference<>();

	@BeforeEach
	void startServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		port = server.getAddress().getPort();
		service = new WmsCapabilitiesService(new WmsCapabilitiesFetcher(mock(WmsUrlGuard.class)));
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private void serveFixture(String contextPath, String fixtureName) {
		server.createContext(contextPath, exchange -> {
			requestedQuery.set(exchange.getRequestURI().getQuery());
			byte[] body = fixture(fixtureName);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	private static byte[] fixture(String name) {
		try (InputStream in = WmsCapabilitiesServiceTest.class.getResourceAsStream("/wms/" + name)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			in.transferTo(out);
			return out.toByteArray();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	@DisplayName("an address without query parameters gets both SERVICE and REQUEST added")
	void addsMissingServiceAndRequestParameters() {
		serveFixture("/wms", "HH_WMS_Cache_Stadtplan.xml");

		WmsDtos.CapabilitiesResponse response = service.capabilities("http://127.0.0.1:" + port + "/wms");

		assertThat(requestedQuery.get()).contains("SERVICE=WMS").contains("REQUEST=GetCapabilities");
		assertThat(response.serviceUrl()).isEqualTo("http://127.0.0.1:" + port + "/wms");
		assertThat(response.layers()).hasSize(1);
	}

	@Test
	@DisplayName("an address that already names SERVICE and REQUEST is left untouched, other parameters included")
	void leavesAnAlreadyCompleteAddressUntouched() {
		serveFixture("/wms", "HH_WMS_Cache_Stadtplan.xml");

		service.capabilities("http://127.0.0.1:" + port + "/wms?token=abc&SERVICE=WMS&REQUEST=GetCapabilities");

		assertThat(requestedQuery.get()).isEqualTo("token=abc&SERVICE=WMS&REQUEST=GetCapabilities");
	}

	@Test
	@DisplayName("the returned serviceUrl never carries a query string, regardless of what was given")
	void serviceUrlNeverCarriesAQueryString() {
		serveFixture("/wms", "HH_WMS_Cache_Stadtplan.xml");

		WmsDtos.CapabilitiesResponse response =
				service.capabilities("http://127.0.0.1:" + port + "/wms?SERVICE=WMS&REQUEST=GetCapabilities");

		assertThat(response.serviceUrl()).isEqualTo("http://127.0.0.1:" + port + "/wms");
	}

	@Test
	@DisplayName("a service without EPSG:3857 surfaces as 422 through the whole chain")
	void aServiceWithoutWebMercatorSurfacesAs422() {
		serveFixture("/wms", "HH_WMS_Cache_Rasterplan.xml");

		assertThatThrownBy(() -> service.capabilities("http://127.0.0.1:" + port + "/wms"))
				.isInstanceOf(de.kreuter.hgis.common.UnprocessableEntityException.class);
	}

	@Test
	@DisplayName("a blank address is refused before any network call")
	void aBlankAddressIsRefused() {
		assertThatThrownBy(() -> service.capabilities("  "))
				.isInstanceOf(BadRequestException.class);
	}

	@Test
	@DisplayName("service and request parameter names are matched case-insensitively")
	void parameterNamesAreCaseInsensitive() {
		serveFixture("/wms", "HH_WMS_Cache_Stadtplan.xml");

		service.capabilities("http://127.0.0.1:" + port + "/wms?service=WMS&request=GetCapabilities");

		assertThat(requestedQuery.get()).isEqualTo("service=WMS&request=GetCapabilities");
	}
}
