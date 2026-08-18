package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The three "no style set" default symbols -- this module's
 * {@link LayerStyleService#DEFAULT_MARKER}/{@code DEFAULT_LINE}/{@code DEFAULT_FILL} and the
 * frontend's {@code defaultSymbols.json} -- must name exactly the same values.
 *
 * <p>Why this test exists at all: {@code LayerStyleService}'s own doc comment on those three
 * constants only promised they were "kept byte for byte identical to the frontend's
 * {@code defaults.ts}" -- a promise nothing checked. {@link #cleanupAfterFieldRemoval} falls
 * back to these three the moment a style's classified field is deleted, so a drift here is
 * exactly as silent, and exactly the same shape of bug, as the one {@link
 * ColorRampCatalogueTest} already guards against: a layer reset by a field removal would
 * render with different colours in the symbology panel's preview than on the map itself, with
 * no error anywhere.
 *
 * <p>Deliberately free of Spring and Testcontainers, same reasoning as {@code
 * ColorRampCatalogueTest}: this is a comparison of two small, static documents, and it should
 * not wait on a database to say the two stacks disagree.
 */
class DefaultSymbolCatalogueTest {

	/**
	 * Relative to {@code backend/}, which is the working directory both the CI job
	 * ({@code working-directory: backend}) and every local {@code ./mvnw} invocation use.
	 * Surefire's forked JVM inherits it rather than starting somewhere of its own.
	 */
	private static final Path FRONTEND_CATALOGUE = Path.of("../frontend/src/styling/defaultSymbols.json");

	@Test
	void theBackendConstantsMatchTheFrontendDefaults() throws IOException {
		assertThat(FRONTEND_CATALOGUE)
				.withFailMessage(
						"""
						The frontend's default-symbol catalogue is not where this test expects it:
						  %s
						Either the file moved, or this test is being run from somewhere other than
						`backend/`. Both stacks read that one file; without it there is nothing to
						compare, and a value changed on one side alone would go unnoticed.""",
						FRONTEND_CATALOGUE.toAbsolutePath().normalize())
				.exists();

		JsonNode catalogue = new ObjectMapper().readTree(Files.readString(FRONTEND_CATALOGUE));

		assertMarkerMatches(catalogue.get("marker"));
		assertLineMatches(catalogue.get("line"));
		assertFillMatches(catalogue.get("fill"));
	}

	/**
	 * `kind` is not a member of the JSON on the frontend side either -- a JSON string literal
	 * widens to plain `string` on import, which cannot satisfy TypeScript's `kind: 'marker'`
	 * discriminant, so `defaults.ts` supplies the literal itself and the object key
	 * (`marker`/`line`/`fill`) is what ties a block to its shape instead. This test mirrors
	 * that: it reads the values under a fixed key, the same way `defaults.ts` does, rather
	 * than expecting a `kind` field that was never written.
	 */
	private void assertMarkerMatches(JsonNode marker) {
		assertThat(marker).withFailMessage("defaultSymbols.json has no `marker` entry").isNotNull();

		/*
		 * TypeScript's excess-property check does not fire on a value that came in through a
		 * JSON import and is then spread into an object literal (measured while building this
		 * package) -- an invented key in this block would sit there unused on the frontend and
		 * compile without a word. Checking the exact key set here is what actually catches it.
		 */
		assertThat(marker.propertyNames())
				.withFailMessage(
						"""
						defaultSymbols.json's `marker` entry carries a key this project does not read: %s
						`MarkerSymbol` in types.ts declares shape, size, fillColor, strokeColor and
						strokeWidth (plus the `kind` literal, supplied in defaults.ts, not in the JSON).
						TypeScript will not catch an invented key here on its own -- its excess property
						check applies to fresh object literals, not to a typed JSON import spread into
						one -- so a stray key would sit there looking meaningful and doing nothing.""",
						marker)
				.containsExactlyInAnyOrder("shape", "size", "fillColor", "strokeColor", "strokeWidth");

		assertThat(marker.get("shape").asString()).isEqualTo(LayerStyleService.DEFAULT_MARKER.shape());
		assertThat(marker.get("size").asDouble()).isEqualTo(LayerStyleService.DEFAULT_MARKER.size());
		assertThat(marker.get("fillColor").asString()).isEqualTo(LayerStyleService.DEFAULT_MARKER.fillColor());
		assertThat(marker.get("strokeColor").asString()).isEqualTo(LayerStyleService.DEFAULT_MARKER.strokeColor());
		assertThat(marker.get("strokeWidth").asDouble()).isEqualTo(LayerStyleService.DEFAULT_MARKER.strokeWidth());
	}

	private void assertLineMatches(JsonNode line) {
		assertThat(line).withFailMessage("defaultSymbols.json has no `line` entry").isNotNull();

		assertThat(line.propertyNames())
				.withFailMessage(
						"""
						defaultSymbols.json's `line` entry carries a key this project does not read: %s
						`LineSymbol` in types.ts declares color and width (plus the optional dashArray,
						which the default deliberately leaves unset, and the `kind` literal, supplied
						in defaults.ts).""",
						line)
				.containsExactlyInAnyOrder("color", "width");

		assertThat(line.get("color").asString()).isEqualTo(LayerStyleService.DEFAULT_LINE.color());
		assertThat(line.get("width").asDouble()).isEqualTo(LayerStyleService.DEFAULT_LINE.width());
	}

	private void assertFillMatches(JsonNode fill) {
		assertThat(fill).withFailMessage("defaultSymbols.json has no `fill` entry").isNotNull();

		assertThat(fill.propertyNames())
				.withFailMessage(
						"""
						defaultSymbols.json's `fill` entry carries a key this project does not read: %s
						`FillSymbol` in types.ts declares fillColor, fillOpacity, outlineColor and
						outlineWidth (plus the `kind` literal, supplied in defaults.ts).""",
						fill)
				.containsExactlyInAnyOrder("fillColor", "fillOpacity", "outlineColor", "outlineWidth");

		assertThat(fill.get("fillColor").asString()).isEqualTo(LayerStyleService.DEFAULT_FILL.fillColor());
		assertThat(fill.get("fillOpacity").asDouble()).isEqualTo(LayerStyleService.DEFAULT_FILL.fillOpacity());
		assertThat(fill.get("outlineColor").asString()).isEqualTo(LayerStyleService.DEFAULT_FILL.outlineColor());
		assertThat(fill.get("outlineWidth").asDouble()).isEqualTo(LayerStyleService.DEFAULT_FILL.outlineWidth());
	}
}
