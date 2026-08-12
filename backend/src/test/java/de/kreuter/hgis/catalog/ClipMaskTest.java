package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure logic for {@link Layer#isClippedBy}, {@link Layer#effectiveMasks} and
 * {@link Layer#clipVersion} (CONTRACT.md phase 21) -- no database needed, since all
 * three methods only ever look at fields already held by the in-memory {@link Layer}
 * instances given to them. The database-backed side of clipping (does {@code
 * MvtService} actually cut the geometry, in each of the four modes, and do several
 * masks combine correctly) lives in {@code tiles.MvtServiceClipTest} and {@code
 * tiles.MvtServiceOutsideClipTest}.
 */
class ClipMaskTest {

	private static final Project PROJECT = new Project("Zuschnitt-Testprojekt", null, 25832, "osm");

	@Test
	void aLayerIsNotClippedWhenNoMaskIsMarked() {
		Layer layer = layer(2);
		assertThat(layer.isClippedBy(null)).isFalse();
		assertThat(layer.effectiveMasks(List.of())).isEmpty();
		assertThat(layer.clipVersion(List.of())).isZero();
	}

	@Test
	void aMaskNeverClipsItself() {
		Layer mask = mask(0, "insideClipped");
		assertThat(mask.isClippedBy(mask)).isFalse();
		assertThat(mask.effectiveMasks(List.of(mask))).isEmpty();
		assertThat(mask.clipVersion(List.of(mask))).isZero();
	}

	@Test
	void aLayerAtTheSameZIndexAsTheMaskIsNotClipped() {
		Layer mask = mask(1, "insideClipped");
		Layer other = layer(1);
		assertThat(other.isClippedBy(mask)).isFalse();
		assertThat(other.clipVersion(List.of(mask))).isZero();
	}

	@Test
	void aLayerBelowTheMaskIsNotClipped() {
		Layer mask = mask(5, "insideClipped");
		Layer below = layer(2);
		assertThat(below.isClippedBy(mask)).isFalse();
		assertThat(below.clipVersion(List.of(mask))).isZero();
	}

	@Test
	void aLayerAboveTheMaskIsClippedWithANonZeroVersion() {
		Layer mask = mask(0, "insideClipped");
		Layer above = layer(1);
		assertThat(above.isClippedBy(mask)).isTrue();
		assertThat(above.effectiveMasks(List.of(mask))).containsExactly(mask);
		assertThat(above.clipVersion(List.of(mask))).isNotZero();
	}

	/**
	 * CONTRACT.md phase 21: any number of masks may act on the same layer at once, and
	 * {@link Layer#effectiveMasks} has to report exactly those below it, in the order
	 * given -- not just the first or the last one it finds.
	 */
	@Test
	void aLayerCanBeClippedByMultipleMasksAtOnce() {
		Layer maskA = mask(0, "insideClipped");
		Layer maskB = mask(1, "outsideClipped");
		Layer above = layer(2);

		assertThat(above.effectiveMasks(List.of(maskA, maskB))).containsExactly(maskA, maskB);
	}

	/** A mask below another mask is itself clipped by it -- masks are not exempt from each other. */
	@Test
	void aMaskAboveAnotherMaskIsClippedByIt() {
		Layer lower = mask(0, "insideClipped");
		Layer upper = mask(1, "outsideClipped");

		assertThat(upper.isClippedBy(lower)).isTrue();
		assertThat(upper.effectiveMasks(List.of(lower, upper))).containsExactly(lower);
	}

	@Test
	void editingAMasksDataChangesTheClipVersion() {
		Layer mask = mask(0, "insideClipped");
		Layer above = layer(1);
		long before = above.clipVersion(List.of(mask));

		mask.bumpDataVersion();

		assertThat(above.clipVersion(List.of(mask))).isNotEqualTo(before);
	}

	@Test
	void aDifferentMaskWithTheSameDataVersionYieldsADifferentClipVersion() {
		Layer above = layer(1);
		Layer maskA = mask(0, "insideClipped");
		Layer maskB = mask(0, "insideClipped");

		// Both freshly constructed masks start at the same dataVersion (1) -- the point
		// of the test: identity, not just dataVersion, has to feed the clipVersion, or
		// two unrelated masks could collide onto the same cache-busting value.
		assertThat(maskA.getDataVersion()).isEqualTo(maskB.getDataVersion());
		assertThat(above.clipVersion(List.of(maskA))).isNotEqualTo(above.clipVersion(List.of(maskB)));
	}

	@Test
	void theSameMaskStateAlwaysYieldsTheSameClipVersion() {
		Layer mask = mask(0, "insideClipped");
		Layer above = layer(3);
		assertThat(above.clipVersion(List.of(mask))).isEqualTo(above.clipVersion(List.of(mask)));
	}

	/**
	 * CONTRACT.md phase 21: clipVersion has to include the mode, not just a mask's
	 * identity and dataVersion -- otherwise switching a mask's mode would leave a
	 * clipped layer's tile URL unchanged, and a client would keep serving the wrongly
	 * clipped tile from its cache.
	 */
	@Test
	void switchingAMasksModeChangesTheClipVersion() {
		Layer mask = mask(0, "insideClipped");
		Layer above = layer(1);
		long before = above.clipVersion(List.of(mask));

		mask.setClipMode("outsideClipped");

		assertThat(above.clipVersion(List.of(mask))).isNotEqualTo(before);
	}

	/** Adding a second mask to the stack must move the clipVersion away from the single-mask value. */
	@Test
	void addingAMaskChangesTheClipVersion() {
		Layer maskA = mask(0, "insideClipped");
		Layer maskB = mask(1, "outsideClipped");
		Layer above = layer(2);

		long withOneMask = above.clipVersion(List.of(maskA));
		long withTwoMasks = above.clipVersion(List.of(maskA, maskB));

		assertThat(withTwoMasks).isNotEqualTo(withOneMask);
	}

	/** The mirror of {@link #addingAMaskChangesTheClipVersion}: removing one has to move it back away. */
	@Test
	void removingAMaskChangesTheClipVersion() {
		Layer maskA = mask(0, "insideClipped");
		Layer maskB = mask(1, "outsideClipped");
		Layer above = layer(2);

		long withTwoMasks = above.clipVersion(List.of(maskA, maskB));
		long withOnlyTheFirst = above.clipVersion(List.of(maskA));

		assertThat(withOnlyTheFirst).isNotEqualTo(withTwoMasks);
	}

	/**
	 * The hash folds masks in with multiplication and addition rather than XOR
	 * specifically so that swapping the order of two masks changes the result -- an XOR
	 * fold would be blind to order, which would in turn make it blind to a mask
	 * inserted or removed at a position that happens to cancel another contribution out.
	 */
	@Test
	void swappingTheOrderOfTwoMasksChangesTheClipVersion() {
		Layer maskA = mask(0, "insideClipped");
		Layer maskB = mask(1, "outsideClipped");
		Layer above = layer(2);

		long orderAB = above.clipVersion(List.of(maskA, maskB));
		long orderBA = above.clipVersion(List.of(maskB, maskA));

		assertThat(orderAB).isNotEqualTo(orderBA);
	}

	private static Layer layer(int zIndex) {
		UUID id = UUID.randomUUID();
		Layer layer = new Layer(id, PROJECT, "Layer " + id, "layer_" + id.toString().replace("-", ""),
				"MULTIPOLYGON", 25832);
		layer.setZIndex(zIndex);
		return layer;
	}

	/** A layer playing the role of one of the project's clip masks, with a mode already set. */
	private static Layer mask(int zIndex, String clipMode) {
		Layer layer = layer(zIndex);
		layer.setClipMode(clipMode);
		return layer;
	}

	/**
	 * clipVersion travels to the browser as a JSON number and lands in the tile URL,
	 * where JavaScript holds it as a double. Past 2^53 that rounds -- in the range a raw
	 * 64-bit hash reaches, to the nearest multiple of 1024. Two mask stacks differing by
	 * less would arrive as the same number, produce the same URL, and never be re-fetched,
	 * because tiles go out {@code immutable} and a client with a matching URL does not ask
	 * at all. The correct ETag behind it would never be consulted.
	 *
	 * <p>Random ids on every run, so this is a different sample each time rather than one
	 * fixed case that happened to pass.
	 */
	@Test
	void clipVersionStaysWithinWhatAJavaScriptNumberHoldsExactly() {
		long maxSafeInteger = 9007199254740991L;

		for (int run = 0; run < 500; run++) {
			Layer first = mask(0, "insideClipped");
			Layer second = mask(1, "outsideWhole");
			Layer top = layer(9);

			long version = top.clipVersion(List.of(first, second));

			assertThat(version)
					.as("clipVersion muss in JavaScript exakt darstellbar bleiben")
					.isPositive()
					.isLessThanOrEqualTo(maxSafeInteger);
			assertThat((double) version)
					.as("Hin und zurueck ueber einen double darf den Wert nicht veraendern")
					.isEqualTo((double) (long) (double) version);
			assertThat((long) (double) version).isEqualTo(version);
		}
	}
}
