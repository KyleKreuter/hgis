package de.kreuter.hgis.geoportal;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The one HTTP client every call to Hamburg's Geoportal goes through: the two catalog
 * files, collection metadata, {@code queryables} and {@code items} pages alike.
 *
 * <p>The read timeout is sized against the plan's own measurement (section 7.1): a page of
 * 10,000 points weighed 7.3 MB and took 2.5 to 3.1 seconds, so 60 seconds leaves headroom
 * for a slower connection or a page of denser geometry without waiting indefinitely on a
 * service that has stopped answering. The User-Agent is what plan section 7.2 asks for --
 * politeness towards a service nothing here has any special agreement with.
 */
@Configuration(proxyBeanMethods = false)
class GeoportalHttpClientConfig {

	private static final String USER_AGENT = "hgis-backend/1.0 (Geoportal Hamburg import, CONTRACT.md phase 23)";

	@Bean
	RestClient geoportalRestClient() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(10));
		requestFactory.setReadTimeout(Duration.ofSeconds(60));
		return RestClient.builder()
				.requestFactory(requestFactory)
				.defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
				.build();
	}
}
