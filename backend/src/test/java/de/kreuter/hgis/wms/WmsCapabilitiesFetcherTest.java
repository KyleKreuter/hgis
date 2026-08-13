package de.kreuter.hgis.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fetch mechanics -- timeout handling aside, which would make this suite slow for no
 * benefit -- against a real local {@link HttpServer} (JDK-provided, no dependency
 * added). {@link WmsUrlGuard} is mocked here, permissive by default: it has its own
 * dedicated tests, and a real guard would refuse every one of these requests outright,
 * since a local test server can only ever bind to loopback.
 */
class WmsCapabilitiesFetcherTest {

	private HttpServer server;
	private int port;

	@BeforeEach
	void startServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		port = server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
			throws java.io.IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	@Test
	@DisplayName("a 200 response is returned as-is")
	void returnsASuccessfulResponseBody() throws Exception {
		server.createContext("/ok", exchange -> respond(exchange, 200, "<xml/>"));
		server.start();
		WmsCapabilitiesFetcher fetcher = new WmsCapabilitiesFetcher(mock(WmsUrlGuard.class));

		byte[] result = fetcher.fetch(uri("/ok"));

		assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("<xml/>");
	}

	@Test
	@DisplayName("a 5xx status is reported as the service not answering")
	void aServerErrorIsUnavailable() throws Exception {
		server.createContext("/error", exchange -> {
			exchange.sendResponseHeaders(503, -1);
			exchange.close();
		});
		server.start();
		WmsCapabilitiesFetcher fetcher = new WmsCapabilitiesFetcher(mock(WmsUrlGuard.class));

		assertThatThrownBy(() -> fetcher.fetch(uri("/error")))
				.isInstanceOf(WmsUnavailableException.class);
	}

	@Test
	@DisplayName("a redirect is followed to its target")
	void followsARedirect() throws Exception {
		server.createContext("/redirect", exchange -> {
			exchange.getResponseHeaders().add("Location", "/target");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		server.createContext("/target", exchange -> respond(exchange, 200, "<final/>"));
		server.start();
		WmsCapabilitiesFetcher fetcher = new WmsCapabilitiesFetcher(mock(WmsUrlGuard.class));

		byte[] result = fetcher.fetch(uri("/redirect"));

		assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("<final/>");
	}

	@Test
	@DisplayName("every hop of a redirect chain is checked by the guard again, not only the first")
	void everyRedirectHopIsRevalidated() throws Exception {
		server.createContext("/first", exchange -> {
			exchange.getResponseHeaders().add("Location", "/second");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		server.createContext("/second", exchange -> respond(exchange, 200, "ok"));
		server.start();
		WmsUrlGuard guard = mock(WmsUrlGuard.class);
		WmsCapabilitiesFetcher fetcher = new WmsCapabilitiesFetcher(guard);

		fetcher.fetch(uri("/first"));

		verify(guard, times(2)).requireAllowed(any());
	}

	@Test
	@DisplayName("a redirect loop is refused instead of running forever")
	void aRedirectLoopIsRefused() throws Exception {
		server.createContext("/loop", exchange -> {
			exchange.getResponseHeaders().add("Location", "/loop");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		server.start();
		WmsCapabilitiesFetcher fetcher = new WmsCapabilitiesFetcher(mock(WmsUrlGuard.class));

		assertThatThrownBy(() -> fetcher.fetch(uri("/loop")))
				.isInstanceOf(WmsUnavailableException.class);
	}

	@Test
	@DisplayName("a body larger than the cap is refused while streaming, not after buffering all of it")
	void aBodyLargerThanTheCapIsRefused() throws Exception {
		server.createContext("/huge", exchange -> {
			long size = WmsCapabilitiesFetcher.MAX_BODY_BYTES + 1024;
			exchange.sendResponseHeaders(200, size);
			byte[] chunk = new byte[64 * 1024];
			try (OutputStream out = exchange.getResponseBody()) {
				long written = 0;
				while (written < size) {
					int n = (int) Math.min(chunk.length, size - written);
					out.write(chunk, 0, n);
					written += n;
				}
			}
		});
		server.start();
		WmsCapabilitiesFetcher fetcher = new WmsCapabilitiesFetcher(mock(WmsUrlGuard.class));

		assertThatThrownBy(() -> fetcher.fetch(uri("/huge")))
				.isInstanceOf(WmsUnavailableException.class);
	}
}
