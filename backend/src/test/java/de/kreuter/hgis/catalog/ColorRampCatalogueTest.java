package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The two colour-ramp catalogues -- this module's {@link LayerStyleService#COLOR_RAMPS} and
 * the frontend's {@code colorRamps.json} -- must name exactly the same ramps.
 *
 * <p>Why this test exists at all: the contract for the heatmap renderer originally said the
 * server kept no ramp catalogue and checked only the length of the name. Every one of the
 * three stacks honoured that faithfully, and the result was that {@code "inferno"} -- the
 * value named in the contract itself, in the Python README's three-line heatmap example and
 * in the docstrings -- painted a *blue* heatmap, because the frontend's own list never held
 * it and {@code styleToMapLibre} fell back to the first entry without a word. Aligning the
 * names fixed that one case; only this test stops the next one.
 *
 * <p>It reads JSON rather than picking ids out of TypeScript source with a regular
 * expression, and that is the point rather than a convenience: a regex over source has two
 * failure modes that both defeat the purpose. It goes quietly empty when a formatter
 * switches quote style -- an empty list matches nothing and the test still passes -- and it
 * counts a commented-out entry as a live one. JSON is parsed, not guessed at.
 *
 * <p>Deliberately free of Spring and Testcontainers: this is a comparison of two lists, and
 * it should not wait on a database to tell anyone that the two stacks disagree.
 */
class ColorRampCatalogueTest {

	/**
	 * Relative to {@code backend/}, which is the working directory both the CI job
	 * ({@code working-directory: backend}) and every local {@code ./mvnw} invocation use.
	 * Surefire's forked JVM inherits it rather than starting somewhere of its own.
	 */
	private static final Path FRONTEND_CATALOGUE = Path.of("../frontend/src/styling/colorRamps.json");

	@Test
	@DisplayName("the backend catalogue names exactly the ramps the frontend offers")
	void bothStacksNameTheSameRamps() throws IOException {
		assertThat(FRONTEND_CATALOGUE)
				.withFailMessage(
						"""
						The frontend's ramp catalogue is not where this test expects it:
						  %s
						Either the file moved, or this test is being run from somewhere other than
						`backend/`. Both stacks read that one file; without it there is nothing to
						compare, and a ramp added on one side alone would go unnoticed.""",
						FRONTEND_CATALOGUE.toAbsolutePath().normalize())
				.exists();

		JsonNode catalogue = new ObjectMapper().readTree(Files.readString(FRONTEND_CATALOGUE));

		/*
		 * Named separately rather than folded into the mapping below: a missing `id` would
		 * otherwise surface as a NullPointerException out of `.asString()`, and whoever reads
		 * the CI log gets a stack trace where this test's whole purpose is to say, in one
		 * sentence, that the two catalogues disagree.
		 */
		for (JsonNode ramp : catalogue) {
			assertThat(ramp.get("id"))
					.withFailMessage("A ramp in colorRamps.json has no `id`: %s", ramp)
					.isNotNull();
			assertThat(ramp.propertyNames())
					.withFailMessage(
							"""
							A ramp in colorRamps.json carries a key this project does not read: %s
							`ColorRamp` in defaults.ts declares id, label and stops, and nothing else acts
							on anything further. TypeScript will not catch this on its own -- its excess
							property check applies to fresh object literals, not to a typed JSON import --
							so an invented key would sit there looking meaningful and doing nothing.""",
							ramp)
					.containsExactlyInAnyOrder("id", "label", "stops");
		}

		List<String> frontendIds = catalogue.valueStream().map(ramp -> ramp.get("id").asString()).toList();

		assertThat(frontendIds)
				.withFailMessage("Two ramps share an id in colorRamps.json: %s", frontendIds)
				.doesNotHaveDuplicates();

		assertThat(new TreeSet<>(frontendIds))
				.withFailMessage(
						"""
						The two ramp catalogues have drifted apart.
						  frontend (colorRamps.json): %s
						  backend  (LayerStyleService.COLOR_RAMPS): %s
						A ramp the frontend offers but the server does not know is rejected with 400
						the moment anyone picks it. One the server accepts but the frontend lacks is
						worse: it is stored, and then drawn in the first ramp's colours without a
						word of warning.""",
						new TreeSet<>(frontendIds), new TreeSet<>(LayerStyleService.COLOR_RAMPS))
				.isEqualTo(new TreeSet<>(LayerStyleService.COLOR_RAMPS));
	}
}
