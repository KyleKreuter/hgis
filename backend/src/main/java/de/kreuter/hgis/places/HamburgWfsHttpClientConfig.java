package de.kreuter.hgis.places;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The one HTTP client every fetch from Hamburg's street/district WFS goes through
 * (CONTRACT.md: {@code geodienste.hamburg.de/HH_WFS_GAGES}). A dedicated bean, the same
 * way {@code geoportal.GeoportalHttpClientConfig} is: a different host, a different
 * expected response size and therefore a different timeout budget than either that one or
 * {@code PhotonHttpClientConfig}.
 *
 * <p>The read timeout is sized against CONTRACT.md's own measurement: the full Strassen
 * extract is 32&nbsp;MB and took 47 seconds live. 180 seconds leaves roughly triple that
 * for a slower connection or a briefly overloaded service, without waiting indefinitely on
 * one that has stopped answering -- this runs inside {@link PlaceRefreshService}'s async
 * job, not on a request thread, so nothing downstream is blocked while it waits.
 */
@Configuration(proxyBeanMethods = false)
class HamburgWfsHttpClientConfig {

	private static final String USER_AGENT = "hgis-backend/1.0 (Ortssuche, Hamburg-Abzug)";

	@Bean
	RestClient hamburgWfsRestClient() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(10));
		requestFactory.setReadTimeout(Duration.ofSeconds(180));
		return RestClient.builder()
				.requestFactory(requestFactory)
				.defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
				.build();
	}
}
