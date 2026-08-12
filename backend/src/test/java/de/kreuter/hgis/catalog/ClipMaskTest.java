package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure logic for {@link Layer#isClippedBy} and {@link Layer#clipVersion} (CONTRACT.md
 * phase 19/20) -- no database needed, since both methods only ever look at fields already
 * held by two in-memory {@link Layer} instances. The database-backed side of clipping
 * (does {@code MvtService} actually cut the geometry, in either mode) lives in
 * {@code tiles.MvtServiceClipTest} and {@code tiles.MvtServiceOutsideClipTest}.
 */
class ClipMaskTest {

	private static final Project PROJECT = new Project("Zuschnitt-Testprojekt", null, 25832, "osm");

	@Test
	void aLayerIsNotClippedWhenNoMaskIsMarked() {
		Layer layer = layer(2);
		assertThat(layer.isClippedBy(null)).isFalse();
		assertThat(layer.clipVersion(null)).isZero();
	}

	@Test
	void aMaskNeverClipsItself() {
		Layer mask = mask(0, "inside");
		assertThat(mask.isClippedBy(mask)).isFalse();
		assertThat(mask.clipVersion(mask)).isZero();
	}

	@Test
	void aLayerAtTheSameZIndexAsTheMaskIsNotClipped() {
		Layer mask = mask(1, "inside");
		Layer other = layer(1);
		assertThat(other.isClippedBy(mask)).isFalse();
		assertThat(other.clipVersion(mask)).isZero();
	}

	@Test
	void aLayerBelowTheMaskIsNotClipped() {
		Layer mask = mask(5, "inside");
		Layer below = layer(2);
		assertThat(below.isClippedBy(mask)).isFalse();
		assertThat(below.clipVersion(mask)).isZero();
	}

	@Test
	void aLayerAboveTheMaskIsClippedWithANonZeroVersion() {
		Layer mask = mask(0, "inside");
		Layer above = layer(1);
		assertThat(above.isClippedBy(mask)).isTrue();
		assertThat(above.clipVersion(mask)).isNotZero();
	}

	@Test
	void editingTheMaskChangesTheClipVersion() {
		Layer mask = mask(0, "inside");
		Layer above = layer(1);
		long before = above.clipVersion(mask);

		mask.bumpDataVersion();

		assertThat(above.clipVersion(mask)).isNotEqualTo(before);
	}

	@Test
	void aDifferentMaskWithTheSameDataVersionYieldsADifferentClipVersion() {
		Layer above = layer(1);
		Layer maskA = mask(0, "inside");
		Layer maskB = mask(0, "inside");

		// Both freshly constructed masks start at the same dataVersion (1) -- the point
		// of the test: identity, not just dataVersion, has to feed the clipVersion, or
		// two unrelated masks could collide onto the same cache-busting value.
		assertThat(maskA.getDataVersion()).isEqualTo(maskB.getDataVersion());
		assertThat(above.clipVersion(maskA)).isNotEqualTo(above.clipVersion(maskB));
	}

	@Test
	void theSameMaskStateAlwaysYieldsTheSameClipVersion() {
		Layer mask = mask(0, "inside");
		Layer above = layer(3);
		assertThat(above.clipVersion(mask)).isEqualTo(above.clipVersion(mask));
	}

	/**
	 * CONTRACT.md phase 20: clipVersion has to include the mode, not just the mask's
	 * identity and dataVersion -- otherwise switching a mask from inside to outside would
	 * leave a clipped layer's tile URL unchanged, and a client would keep serving the
	 * wrongly clipped tile from its cache.
	 */
	@Test
	void switchingTheMasksModeChangesTheClipVersion() {
		Layer mask = mask(0, "inside");
		Layer above = layer(1);
		long insideVersion = above.clipVersion(mask);

		mask.setClipMode("outside");

		assertThat(above.clipVersion(mask)).isNotEqualTo(insideVersion);
	}

	private static Layer layer(int zIndex) {
		UUID id = UUID.randomUUID();
		Layer layer = new Layer(id, PROJECT, "Layer " + id, "layer_" + id.toString().replace("-", ""),
				"MULTIPOLYGON", 25832);
		layer.setZIndex(zIndex);
		return layer;
	}

	/** A layer playing the role of the project's clip mask, with a mode already set. */
	private static Layer mask(int zIndex, String clipMode) {
		Layer layer = layer(zIndex);
		layer.setClipMode(clipMode);
		return layer;
	}
}
