package de.kreuter.hgis.ingest.reader;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.ingest.spi.SourceSchema.CrsConfidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;

class CrsDetectorTest {

	@Test
	@DisplayName("hält geographische Gradwerte für plausibel als EPSG:4326")
	void plausibleGeographic() {
		Envelope hamburg = new Envelope(9.9, 10.1, 53.4, 53.6);
		CrsDetector.Detection detection = CrsDetector.assumed(4326, hamburg);
		assertThat(detection.srid()).isEqualTo(4326);
		assertThat(detection.confidence()).isEqualTo(CrsConfidence.ASSUMED);
	}

	@Test
	@DisplayName("erkennt UTM-Koordinaten als unplausibel für EPSG:4326 und rät stattdessen 25832")
	void implausibleGeographicFallsBackToUtmZone32() {
		// The example from the spec: easting/northing that cannot possibly be lon/lat.
		Envelope utmLike = new Envelope(502000, 502100, 5720000, 5720100);
		CrsDetector.Detection detection = CrsDetector.assumed(4326, utmLike);
		assertThat(detection.confidence()).isEqualTo(CrsConfidence.GUESSED);
		assertThat(detection.srid()).isEqualTo(25832);
	}

	@Test
	@DisplayName("erkennt eine mit der Zonennummer 32 vorangestellte Rechtswert-Angabe")
	void zonePrefixed32() {
		Envelope prefixed = new Envelope(32_400_000, 32_400_100, 5_700_000, 5_700_100);
		assertThat(CrsDetector.guessSrid(prefixed)).isEqualTo(25832);
	}

	@Test
	@DisplayName("erkennt eine mit der Zonennummer 33 vorangestellte Rechtswert-Angabe")
	void zonePrefixed33() {
		Envelope prefixed = new Envelope(33_400_000, 33_400_100, 5_700_000, 5_700_100);
		assertThat(CrsDetector.guessSrid(prefixed)).isEqualTo(25833);
	}

	@Test
	@DisplayName("liefert ohne jede Probe 4326 als sicheren Standardwert")
	void emptyBboxDefaultsToWgs84() {
		assertThat(CrsDetector.guessSrid(new Envelope())).isEqualTo(4326);
	}

	@Test
	@DisplayName("declared vertraut dem übergebenen SRID ohne Prüfung")
	void declaredTrustsInput() {
		CrsDetector.Detection detection = CrsDetector.declared(31467);
		assertThat(detection.srid()).isEqualTo(31467);
		assertThat(detection.confidence()).isEqualTo(CrsConfidence.DECLARED);
	}

	@Test
	@DisplayName("guess liefert immer GUESSED, auch wenn der Wertebereich zu 4326 passt")
	void guessAlwaysReportsGuessed() {
		CrsDetector.Detection detection = CrsDetector.guess(new Envelope(9.9, 10.1, 53.4, 53.6));
		assertThat(detection.confidence()).isEqualTo(CrsConfidence.GUESSED);
		assertThat(detection.srid()).isEqualTo(4326);
	}
}
