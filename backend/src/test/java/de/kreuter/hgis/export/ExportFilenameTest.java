package de.kreuter.hgis.export;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The layer name is the one piece of an export that a user wrote, and it ends up in a
 * response header. These tests are about what must not survive that trip.
 */
class ExportFilenameTest {

	@Test
	void keepsAnOrdinaryNameAsItIs() {
		assertThat(ExportFilename.asciiFilename("Stadtteile", "geojson"))
				.isEqualTo("Stadtteile.geojson");
	}

	@Test
	@DisplayName("umlauts are spelled out for the ASCII name and kept in filename*")
	void transliteratesForAsciiAndPreservesTheOriginal() {
		String disposition = ExportFilename.contentDisposition("Bevölkerung", "geojson");

		assertThat(disposition).contains("filename=\"Bevoelkerung.geojson\"");
		assertThat(disposition)
				.as("UTF-8 bytes of ö, percent-encoded")
				.contains("filename*=UTF-8''Bev%C3%B6lkerung.geojson");
	}

	@Test
	@DisplayName("a CRLF in the layer name cannot end the header")
	void neverEmitsALineBreak() {
		String disposition = ExportFilename.contentDisposition(
				"Harmlos\r\nX-Injected: yes", "geojson");

		assertThat(disposition).doesNotContain("\r").doesNotContain("\n");
		assertThat(disposition).doesNotContain("X-Injected: yes");
	}

	@Test
	@DisplayName("a quote or semicolon cannot end the quoted filename parameter")
	void neverEmitsAnUnescapedDelimiter() {
		String disposition = ExportFilename.contentDisposition("a\"; b=c", "geojson");

		// Exactly two quotes: the pair around the ASCII filename, and none from the name
		// itself -- so nothing after it can be read as another parameter.
		assertThat(disposition.chars().filter(character -> character == '"').count()).isEqualTo(2);
		assertThat(disposition).contains("filename=\"a_b_c.geojson\"");
		assertThat(disposition).contains("filename*=UTF-8''a%22%3B%20b%3Dc.geojson");
	}

	@Test
	@DisplayName("path separators never reach either form of the name")
	void stripsPathSeparators() {
		String disposition = ExportFilename.contentDisposition("../../etc/passwd", "geojson");

		assertThat(disposition).contains("filename=\"etc_passwd.geojson\"");
		assertThat(ExportFilename.readableFilename("../../etc/passwd", "geojson"))
				.isEqualTo(".._.._etc_passwd.geojson");
	}

	@Test
	@DisplayName("a name of nothing usable still yields a file name")
	void fallsBackForAnUnusableName() {
		assertThat(ExportFilename.asciiFilename("...", "geojson")).isEqualTo("layer.geojson");
		assertThat(ExportFilename.asciiFilename("   ", "geojson")).isEqualTo("layer.geojson");
		assertThat(ExportFilename.asciiFilename("", "geojson")).isEqualTo("layer.geojson");
		assertThat(ExportFilename.asciiFilename(null, "geojson")).isEqualTo("layer.geojson");
	}

	@Test
	void truncatesAnAbsurdlyLongName() {
		String name = "L".repeat(500);

		assertThat(ExportFilename.asciiFilename(name, "geojson")).hasSize(80 + ".geojson".length());
		assertThat(ExportFilename.readableFilename(name, "geojson"))
				.hasSize(80 + ".geojson".length());
	}

	@Test
	@DisplayName("percent-encoding is RFC 5987, not form encoding")
	void encodesASpaceAsPercentTwenty() {
		// URLEncoder would write a plus here, which a browser saves as a literal plus.
		assertThat(ExportFilename.percentEncode("a b")).isEqualTo("a%20b");
	}
}
