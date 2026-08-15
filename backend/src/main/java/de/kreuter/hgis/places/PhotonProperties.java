package de.kreuter.hgis.places;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code hgis.places.photon.*} in application.yml (CONTRACT.md: "Adresse konfigurierbar
 * ..., abschaltbar ..., Standard: an, öffentlicher Endpunkt"). Registered by
 * {@link PhotonHttpClientConfig}'s {@code @EnableConfigurationProperties} rather than on
 * the main application class: this is the only configuration-properties bean in the
 * application so far, and registering it there keeps that decision local to this package
 * instead of touching a file every other package would otherwise have a reason to edit too.
 * Plain {@code @Component} does not work for a constructor-bound (record) properties class
 * -- it needs the dedicated registration path {@code @EnableConfigurationProperties}
 * provides, or Spring tries to satisfy {@code url}/{@code enabled} as ordinary autowired
 * dependencies and fails to start.
 */
@ConfigurationProperties(prefix = "hgis.places.photon")
record PhotonProperties(
		@DefaultValue("https://photon.komoot.io/api/") String url,
		@DefaultValue("true") boolean enabled) {
}
