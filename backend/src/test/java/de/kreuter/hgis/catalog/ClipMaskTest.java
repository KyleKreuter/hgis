package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure logic for {@link Layer#isClippedBy} and {@link Layer#clipVersion} (CONTRACT.md
 * phase 19) -- no database needed, since both methods only ever look at fields already
 * held by two in-memory {@link Layer} instances. The database-backed side of clipping
 * (does {@code MvtService} actually cut the geometry) lives in
 * {@code tiles.MvtServiceClipTest}.
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
		Layer mask = layer(0);
		assertThat(mask.isClippedBy(mask)).isFalse();
		assertThat(mask.clipVersion(mask)).isZero();
	}

	@Test
	void aLayerAtTheSameZIndexAsTheMaskIsNotClipped() {
		Layer mask = layer(1);
		Layer other = layer(1);
		assertThat(other.isClippedBy(mask)).isFalse();
		assertThat(other.clipVersion(mask)).isZero();
	}

	@Test
	void aLayerBelowTheMaskIsNotClipped() {
		Layer mask = layer(5);
		Layer below = layer(2);
		assertThat(below.isClippedBy(mask)).isFalse();
		assertThat(below.clipVersion(mask)).isZero();
	}

	@Test
	void aLayerAboveTheMaskIsClippedWithANonZeroVersion() {
		Layer mask = layer(0);
		Layer above = layer(1);
		assertThat(above.isClippedBy(mask)).isTrue();
		assertThat(above.clipVersion(mask)).isNotZero();
	}

	@Test
	void editingTheMaskChangesTheClipVersion() {
		Layer mask = layer(0);
		Layer above = layer(1);
		long before = above.clipVersion(mask);

		mask.bumpDataVersion();

		assertThat(above.clipVersion(mask)).isNotEqualTo(before);
	}

	@Test
	void aDifferentMaskWithTheSameDataVersionYieldsADifferentClipVersion() {
		Layer above = layer(1);
		Layer maskA = layer(0);
		Layer maskB = layer(0);

		// Both freshly constructed masks start at the same dataVersion (1) -- the point
		// of the test: identity, not just dataVersion, has to feed the clipVersion, or
		// two unrelated masks could collide onto the same cache-busting value.
		assertThat(maskA.getDataVersion()).isEqualTo(maskB.getDataVersion());
		assertThat(above.clipVersion(maskA)).isNotEqualTo(above.clipVersion(maskB));
	}

	@Test
	void theSameMaskStateAlwaysYieldsTheSameClipVersion() {
		Layer mask = layer(0);
		Layer above = layer(3);
		assertThat(above.clipVersion(mask)).isEqualTo(above.clipVersion(mask));
	}

	private static Layer layer(int zIndex) {
		UUID id = UUID.randomUUID();
		Layer layer = new Layer(id, PROJECT, "Layer " + id, "layer_" + id.toString().replace("-", ""),
				"MULTIPOLYGON", 25832);
		layer.setZIndex(zIndex);
		return layer;
	}
}
