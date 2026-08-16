package de.kreuter.hgis.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code hgis.events.*} in application.yml. Registered by {@link EventsConfig}, the same
 * way {@code PhotonProperties} is registered by its own package's configuration -- see
 * the note there for why a plain {@code @Component} does not work for a record.
 *
 * @param maxStreams how many live channels may be open at once before the server turns
 *     further ones away. One browser tab holds one, so this is a count of open
 *     workspaces, not of users. Reached only by something that opens streams and never
 *     closes them, which is exactly the case worth refusing.
 * @param streamTimeout how long one connection lives before the server ends it. Not a
 *     limit on the feature: SSE clients reconnect by themselves, and this is what turns
 *     a client that vanished without closing its socket into a connection the server
 *     eventually gets back. Every reconnect also re-reads the current state, so the gap
 *     costs nothing but a moment.
 * @param retry how long a client should wait before reconnecting, sent as the stream's
 *     {@code retry:} field. The browser's own default is around three seconds; naming it
 *     means the value is ours rather than the browser's.
 */
@ConfigurationProperties(prefix = "hgis.events")
record EventProperties(
		@DefaultValue("100") int maxStreams,
		@DefaultValue("5m") Duration streamTimeout,
		@DefaultValue("3s") Duration retry) {
}
