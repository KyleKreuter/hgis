package de.kreuter.hgis.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checked against real, small extracts of Hamburg's WFS -- CONTRACT.md asks for a fixture
 * fetched with {@code COUNT=50}, saved once and read from {@code src/test/resources/places}
 * from then on, the same way {@code WmsCapabilitiesParserTest} does for its four documents.
 * No test here touches the network.
 *
 * <p>{@code strassen_sample50.xml} is exactly that {@code COUNT=50} extract; the other
 * three street fixtures are small, hand-picked subsets of one much larger real extract
 * (COUNT=10000, fetched once) rather than second live requests -- CONTRACT.md separately
 * asks to be sparing with outbound requests, and a single street or two, still taken
 * verbatim from a real response, needs no second fetch of its own.
 */
class PlaceGmlReaderTest {

	private final PlaceGmlReader reader = new PlaceGmlReader();

	private static InputStream fixture(String name) {
		try (InputStream in = PlaceGmlReaderTest.class.getResourceAsStream("/places/" + name)) {
			if (in == null) {
				throw new IllegalStateException("Test fixture missing: " + name);
			}
			return new ByteArrayInputStream(in.readAllBytes());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Test
	@DisplayName("fifty dog:Strassen members become fifty-one place rows: one street among them crosses two postal codes")
	void fiftyMembersProduceFiftyOneRows() {
		var places = reader.readStrassen(fixture("strassen_sample50.xml"));

		assertThat(places).hasSize(51);
	}

	@Test
	@DisplayName("CONTRACT.md's own worked example: name, context and the 25832 position it names, read correctly")
	void parsesNameContextAndPositionAsContractDescribes() {
		var places = reader.readStrassen(fixture("strassen_sample50.xml"));

		var achterdwars = places.stream().filter(p -> p.name().equals("Achterdwars")).findFirst().orElseThrow();
		assertThat(achterdwars.context()).isEqualTo("Bergedorf, 21035");
		assertThat(achterdwars.kind()).isEqualTo("street");
		// CONTRACT.md's gml:pos example, taken from iso19112:position -- not
		// iso19112:position_strassenachse, whose value for this same street differs
		// (579684.553 5927041.310, confirmed live) and must not be picked up instead.
		assertThat(achterdwars.x25832()).isCloseTo(579684.552, within(0.001));
		assertThat(achterdwars.y25832()).isCloseTo(5927090.528, within(0.001));
	}

	@Test
	@DisplayName("a street spanning two postal-code areas becomes two rows, same name, paired context each")
	void aStreetWithTwoPostalCodesBecomesTwoSegments() {
		var places = reader.readStrassen(fixture("strassen_akeleiweg.xml"));

		assertThat(places).extracting("name", "context", "kind")
				.containsExactly(
						tuple("Akeleiweg", "Bahrenfeld, 22607", "street"),
						tuple("Akeleiweg", "Lurup, 22549", "street"));
		// Hamburg's WFS gives only one position for the whole feature, regardless of how
		// many postal-code segments it has -- both rows carry it.
		assertThat(places).allSatisfy(p -> {
			assertThat(p.x25832()).isCloseTo(557535.109, within(0.001));
			assertThat(p.y25832()).isCloseTo(5937169.344, within(0.001));
		});
	}

	@Test
	@DisplayName("Hauptstra finds what the amtliche WFS's own text search cannot -- read correctly here is what makes the trigram search possible")
	void substringCandidatesForHauptstraParseCleanly() {
		var places = reader.readStrassen(fixture("strassen_hauptstrasse.xml"));

		assertThat(places).extracting("name")
				.containsExactly("Billstedter Hauptstraße", "Hummelsbüttler Hauptstraße",
						"Luruper Hauptstraße 180-Parkanlagen");
		assertThat(places).allSatisfy(p -> assertThat(p.name()).contains("Hauptstra"));
	}

	@Test
	@DisplayName("a street with no postal code on file still becomes one row, with a null context rather than being dropped")
	void aStreetWithNoPostalCodeGetsANullContext() {
		var places = reader.readStrassen(fixture("strassen_ohne_plz.xml"));

		assertThat(places).hasSize(2);
		assertThat(places).extracting("name").containsExactly("Herulerweg", "Rahlstedter Heideweg");
		assertThat(places).allSatisfy(p -> assertThat(p.context()).isNull());
	}

	@Test
	@DisplayName("districts are named from iso19112:parent, the clean form -- not dog:ortsteilname's ',OT nnnn' machine key")
	void districtsUseTheCleanParentName() {
		var places = reader.readOrtsteile(fixture("ortsteile_sample20.xml"));

		assertThat(places).hasSize(20);
		assertThat(places.get(0).name()).isEqualTo("Hamburg-Altstadt");
		assertThat(places.get(0).kind()).isEqualTo("district");
		assertThat(places.get(0).context()).isNull();
		assertThat(places).allSatisfy(p -> assertThat(p.name()).doesNotContain("OT "));
	}
}
