package de.kreuter.hgis.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kreuter.hgis.common.BadRequestException;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SSRF guard, checked in isolation and entirely offline: every case below uses a
 * literal IP address in the URI, which the JDK's {@link java.net.InetAddress#getAllByName}
 * recognises and parses without a DNS lookup -- and the one hostname case,
 * {@code localhost}, resolves purely from the machine's own hosts file, never the
 * network. Nothing here reaches out.
 */
class WmsUrlGuardTest {

	private final WmsUrlGuard guard = new WmsUrlGuard();

	@ParameterizedTest
	@DisplayName("private, loopback and link-local addresses are refused")
	@ValueSource(strings = {
			"http://127.0.0.1",
			"http://127.0.0.1/GetCapabilities",
			"http://127.5.9.200",
			"http://10.0.0.1",
			"http://10.255.255.255",
			"http://172.16.0.5",
			"http://172.31.255.255",
			"http://192.168.1.1",
			"http://192.168.255.255",
			"http://169.254.1.1",
			"http://[::1]",
			"http://[fc00::1]",
			"http://[fd00::1]",
			"http://[fe80::1]",
			"http://0.0.0.0",
			"http://224.0.0.1" })
	void refusesPrivateAndLoopbackAddresses(String url) {
		assertThatThrownBy(() -> guard.requireAllowed(URI.create(url)))
				.isInstanceOf(BadRequestException.class)
				.hasMessage("Diese Adresse ist nicht erlaubt.");
	}

	@Test
	@DisplayName("a hostname that resolves to loopback is refused, not just a literal 127.0.0.1")
	void refusesAHostnameThatResolvesToLoopback() {
		// "localhost" resolves via the OS hosts file, not a real DNS query -- offline-safe
		// and true on every platform this backend runs on.
		assertThatThrownBy(() -> guard.requireAllowed(URI.create("http://localhost:8080/GetCapabilities")))
				.isInstanceOf(BadRequestException.class)
				.hasMessage("Diese Adresse ist nicht erlaubt.");
	}

	@ParameterizedTest
	@DisplayName("only http and https are allowed schemes")
	@ValueSource(strings = { "file:///etc/passwd", "ftp://geodienste.hamburg.de/foo", "javascript:alert(1)" })
	void refusesAnythingButHttpAndHttps(String url) {
		assertThatThrownBy(() -> guard.requireAllowed(URI.create(url)))
				.isInstanceOf(BadRequestException.class)
				.hasMessage("Diese Adresse ist nicht erlaubt.");
	}

	@Test
	@DisplayName("a URI with no host at all is refused")
	void refusesAUriWithNoHost() {
		assertThatThrownBy(() -> guard.requireAllowed(URI.create("file:relative/path")))
				.isInstanceOf(BadRequestException.class);
	}

	@ParameterizedTest
	@DisplayName("public IPv4/IPv6 literals pass -- no DNS lookup involved, so this stays offline")
	@ValueSource(strings = { "http://8.8.8.8", "https://93.184.216.34", "http://1.1.1.1:80/GetCapabilities" })
	void allowsPublicAddresses(String url) {
		assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> guard.requireAllowed(URI.create(url))))
				.isNull();
	}
}
