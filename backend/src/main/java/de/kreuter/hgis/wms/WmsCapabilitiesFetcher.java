package de.kreuter.hgis.wms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Fetches one WMS service's GetCapabilities document -- the only thing this backend ever
 * asks of an address a client supplied rather than one of its own fixed hosts, and
 * therefore the one outbound call in the whole application that has to defend itself
 * against the target instead of trusting it.
 *
 * <p>Built on the JDK's own {@link HttpClient} rather than the {@code RestClient} every
 * other package uses: redirects have to be inspected and re-validated one hop at a time
 * (see {@link WmsUrlGuard}'s class doc for why "never blindly follow" matters here and
 * nowhere else in this backend), and the response has to be capped while it streams
 * rather than after Spring has already buffered all of it -- neither is a knob
 * {@code SimpleClientHttpRequestFactory} exposes.
 */
@Component
class WmsCapabilitiesFetcher {

	private static final String USER_AGENT = "hgis-backend/1.0 (WMS-Kartenbild, GetCapabilities)";

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Read timeout. Generous next to the plan's own measurement of a capabilities
	 * document (6 KB to 73 KB) -- this is a budget for a slow or overloaded service to
	 * still answer, not an estimate of how long a normal one takes.
	 */
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

	/**
	 * Every capabilities document the plan measured live was between 6 KB and 73 KB;
	 * this leaves generous room above that for a service with an unusually long layer
	 * list while still refusing to stream an unbounded response into memory.
	 */
	static final long MAX_BODY_BYTES = 5L * 1024 * 1024;

	/** However many hops a legitimate service might reasonably use (e.g. http -> https, a CDN move). */
	private static final int MAX_REDIRECTS = 5;

	private static final String UNAVAILABLE_MESSAGE = "Der Dienst hat nicht geantwortet.";

	private final WmsUrlGuard urlGuard;

	/**
	 * {@code Redirect.NEVER}: a redirect response is inspected and re-validated by hand
	 * in {@link #fetch}, which is the whole point -- the built-in {@code NORMAL} policy
	 * would follow a {@code Location} straight past {@link WmsUrlGuard} and defeat it.
	 */
	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	WmsCapabilitiesFetcher(WmsUrlGuard urlGuard) {
		this.urlGuard = urlGuard;
	}

	/**
	 * @return the response body, at most {@link #MAX_BODY_BYTES}
	 * @throws de.kreuter.hgis.common.BadRequestException the address, or a redirect
	 *     target along the way, is not allowed (see {@link WmsUrlGuard})
	 * @throws WmsUnavailableException no usable answer within the hop and size budget:
	 *     a timeout, a connection failure, a non-2xx status, too many redirects, or a
	 *     body larger than {@link #MAX_BODY_BYTES}
	 */
	byte[] fetch(URI uri) {
		return fetch(uri, 0);
	}

	private byte[] fetch(URI uri, int redirectCount) {
		urlGuard.requireAllowed(uri);

		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(READ_TIMEOUT)
				.header("User-Agent", USER_AGENT)
				.GET()
				.build();

		HttpResponse<InputStream> response;
		try {
			response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new WmsUnavailableException(UNAVAILABLE_MESSAGE);
		}
		catch (IOException e) {
			throw new WmsUnavailableException(UNAVAILABLE_MESSAGE);
		}

		int status = response.statusCode();
		if (status >= 300 && status < 400) {
			return followRedirect(response, redirectCount);
		}
		if (status < 200 || status >= 300) {
			drain(response.body());
			throw new WmsUnavailableException(UNAVAILABLE_MESSAGE);
		}
		return readCapped(response.body());
	}

	/**
	 * Resolves {@code Location} against the request it answered (it may be relative)
	 * and re-enters {@link #fetch}, which runs {@link WmsUrlGuard} again on the new
	 * target before anything is sent to it -- a redirect to a private address is
	 * refused exactly as if the client had asked for it directly.
	 */
	private byte[] followRedirect(HttpResponse<InputStream> response, int redirectCount) {
		drain(response.body());
		if (redirectCount >= MAX_REDIRECTS) {
			throw new WmsUnavailableException(UNAVAILABLE_MESSAGE);
		}
		String location = response.headers().firstValue("Location").orElse(null);
		if (location == null || location.isBlank()) {
			throw new WmsUnavailableException(UNAVAILABLE_MESSAGE);
		}
		URI target;
		try {
			target = response.request().uri().resolve(location);
		}
		catch (IllegalArgumentException e) {
			throw new WmsUnavailableException(UNAVAILABLE_MESSAGE);
		}
		return fetch(target, redirectCount + 1);
	}

	/** Enforces {@link #MAX_BODY_BYTES} while streaming, not after buffering an unbounded body. */
	private static byte[] readCapped(InputStream in) {
		try (in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			long total = 0;
			int read;
			while ((read = in.read(buffer)) != -1) {
				total += read;
				if (total > MAX_BODY_BYTES) {
					throw new WmsUnavailableException(UNAVAILABLE_MESSAGE);
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new WmsUnavailableException(UNAVAILABLE_MESSAGE);
		}
	}

	/** The body of a response this method is about to discard still has to be closed properly. */
	private static void drain(InputStream in) {
		try (in) {
			in.readAllBytes();
		}
		catch (IOException ignored) {
			// The connection is being abandoned either way; nothing downstream reads this body.
		}
	}
}
