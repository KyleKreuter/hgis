package de.kreuter.hgis.places;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The one HTTP client every call to Photon goes through. CONTRACT.md's 2-second budget is
 * tight on purpose: Photon is a donated public service with no SLA towards this
 * application, and every {@code GET /api/places} request waits on it inline (unlike the
 * Hamburg WFS, which only ever runs inside the async refresh job) -- a slow Photon must
 * never make the whole search feel broken, so {@link PlaceSearchService} treats a timeout
 * here exactly like any other Photon failure: log it, return the Hamburg hits anyway.
 *
 * <p>Also where {@link PhotonProperties} is registered -- see its own class doc for why.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PhotonProperties.class)
class PhotonHttpClientConfig {

	private static final String USER_AGENT = "hgis-backend/1.0 (Ortssuche, Photon)";

	@Bean
	RestClient photonRestClient() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(2));
		requestFactory.setReadTimeout(Duration.ofSeconds(2));
		return RestClient.builder()
				.requestFactory(requestFactory)
				.defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
				.build();
	}
}
