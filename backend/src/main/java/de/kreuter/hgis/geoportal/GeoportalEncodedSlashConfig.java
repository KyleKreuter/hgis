package de.kreuter.hgis.geoportal;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A dataset id can carry a literal {@code /} (CatalogLoader's own doing, e.g. {@code
 * strassenbaumkataster/strassenbaumkataster_hh} -- see {@link GeoportalCatalogController}),
 * so a client that URL-encodes it correctly sends {@code %2F}. Embedded Tomcat's connector
 * rejects that outright with a bare 400 before the request ever reaches Spring or {@link
 * GeoportalCatalogController} -- {@link org.apache.tomcat.util.buf.EncodedSolidusHandling}
 * defaults to {@code REJECT}, a guard against request-smuggling and path-traversal tricks
 * that hide a {@code /} from a servlet-path-based security check. This backend has none:
 * no Spring Security, no filter that trusts the decoded servlet path over what a
 * {@code @RequestMapping} actually matched.
 *
 * <p>Chosen here is {@code PASS_THROUGH}, not {@code DECODE}, and deliberately so.
 * {@code DECODE} would have Tomcat itself turn every {@code %2F} in every request's URI
 * into a literal {@code /} before Spring ever sees it -- a change with the same shape
 * everywhere in the application, including a route this backend does not control the
 * meaning of yet. {@code PASS_THROUGH} only stops the rejection; the encoded triplet
 * still arrives exactly as sent, and nothing decodes it on this route's behalf until
 * {@link GeoportalCatalogController} chooses to. Every other endpoint keeps behaving as
 * if this class did not exist -- none of them has a path variable that expects an encoded
 * {@code /} in the first place, so there is nothing here for {@code PASS_THROUGH} to
 * change for them.
 *
 * <p>Applies to the one embedded connector this application runs, so it is unavoidably a
 * server-wide setting -- Tomcat offers no per-path variant. What matters is that its
 * effect is a no-op for every path that never sends {@code %2F}, which is every path but
 * this one.
 */
@Configuration(proxyBeanMethods = false)
class GeoportalEncodedSlashConfig {

	@Bean
	WebServerFactoryCustomizer<TomcatServletWebServerFactory> encodedSolidusPassThrough() {
		return factory -> factory.addConnectorCustomizers(
				(Connector connector) -> connector.setEncodedSolidusHandling("passthrough"));
	}
}
