package de.kreuter.hgis.basemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** In the same package on purpose: {@link BasemapUrlTemplate} is package-private, see its class doc. */
class BasemapUrlTemplateTest {

	@Test
	void isUrlTemplateAcceptsOnlyAnHttpsPrefix() {
		assertThat(BasemapUrlTemplate.isUrlTemplate("https://tiles.example.org/{z}/{x}/{y}.png")).isTrue();
		assertThat(BasemapUrlTemplate.isUrlTemplate("http://tiles.example.org/{z}/{x}/{y}.png")).isFalse();
		assertThat(BasemapUrlTemplate.isUrlTemplate("osm")).isFalse();
		assertThat(BasemapUrlTemplate.isUrlTemplate("javascript:alert(1)")).isFalse();
		assertThat(BasemapUrlTemplate.isUrlTemplate("data:text/html,x")).isFalse();
	}

	@Test
	void acceptsAWellFormedXyzTemplate() {
		assertThatCode(() -> BasemapUrlTemplate.requireValid("https://tiles.example.org/{z}/{x}/{y}.png"))
				.doesNotThrowAnyException();
	}

	/** WMTS-KVP style templates put {@code {y}} before {@code {x}} -- must not be rejected for that. */
	@Test
	void acceptsAWmtsStyleTemplateWithYBeforeX() {
		assertThatCode(() -> BasemapUrlTemplate.requireValid("https://example.org/wmts/{z}/{y}/{x}.png"))
				.doesNotThrowAnyException();
	}

	/**
	 * Form B (VERTRAG.md "Zwei Formen von urlTemplate", 27.08.): a WMS-GetMap template,
	 * {@code {bbox-epsg-3857}} instead of {@code {z}}/{@code {x}}/{@code {y}} -- the shape
	 * Hamburg's aerial imagery needs, since it exists only as WMS.
	 */
	@Test
	void acceptsAWmsGetMapTemplate() {
		assertThatCode(() -> BasemapUrlTemplate.requireValid(
				"https://geodienste.hamburg.de/wms_dop?SERVICE=WMS&REQUEST=GetMap&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256"))
				.doesNotThrowAnyException();
	}

	/** Both placeholder sets at once is pointless but not rejected -- see the class doc. */
	@Test
	void acceptsATemplateWithBothPlaceholderForms() {
		assertThatCode(() -> BasemapUrlTemplate.requireValid(
				"https://tiles.example.org/{z}/{x}/{y}.png?bbox={bbox-epsg-3857}"))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsAMissingPlaceholder() {
		assertThatThrownBy(() -> BasemapUrlTemplate.requireValid("https://tiles.example.org/{z}/{x}/fixed.png"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Die URL-Vorlage muss entweder {z}, {x} und {y} oder {bbox-epsg-3857} enthalten.");
	}

	@Test
	void rejectsATemplateWithNoPlaceholdersAtAll() {
		assertThatThrownBy(() -> BasemapUrlTemplate.requireValid("https://tiles.example.org/fixed.png"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Die URL-Vorlage muss entweder {z}, {x} und {y} oder {bbox-epsg-3857} enthalten.");
	}

	@Test
	void rejectsCredentialsInTheUrl() {
		assertThatThrownBy(() -> BasemapUrlTemplate.requireValid("https://user:pass@tiles.example.org/{z}/{x}/{y}.png"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Die URL-Vorlage darf keine Zugangsdaten enthalten.");
	}

	@Test
	void rejectsAMissingHost() {
		assertThatThrownBy(() -> BasemapUrlTemplate.requireValid("https:///{z}/{x}/{y}.png"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Die URL-Vorlage muss einen Hostnamen enthalten.");
	}

	@Test
	void rejectsAnOverlongTemplate() {
		String url = "https://tiles.example.org/" + "a".repeat(2000) + "/{z}/{x}/{y}.png";
		assertThatThrownBy(() -> BasemapUrlTemplate.requireValid(url))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Die URL-Vorlage darf höchstens 2000 Zeichen lang sein.");
	}

	@Test
	void rejectsAStructurallyInvalidUrl() {
		assertThatThrownBy(() -> BasemapUrlTemplate.requireValid("https://tiles.example.org/{z}/{x}/{y}.png ##{z}{x}{y}"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Die URL-Vorlage ist keine gültige Adresse.");
	}
}
