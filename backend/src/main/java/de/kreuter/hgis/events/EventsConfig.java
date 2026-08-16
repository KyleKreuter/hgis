package de.kreuter.hgis.events;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link EventProperties}; see the note there. Nothing else belongs here. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventProperties.class)
class EventsConfig {
}
