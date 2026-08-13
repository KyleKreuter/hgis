package de.kreuter.hgis.wms;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.wms.dto.WmsDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scale-denominator to zoom-level arithmetic, on its own.
 *
 * <p>This is what decides whether a map image is ever drawn: a layer whose window starts
 * two levels too high is added successfully, listed in the layer tree, and shows nothing.
 * That is how it surfaced -- reported as "I load one from Hamburg and see nothing".
 */
class MapLayerZoomRangeTest {

	/** Hamburg's own latitude band, as `HH_WMS_Geobasiskarten` declares it for its tiers. */
	private static final double[] HAMBURG = { 8.405498, 53.384725, 10.432697, 53.968392 };

	private static WmsDtos.Layer layer(Double minScale, Double maxScale, double[] bbox) {
		return new WmsDtos.Layer("x", "Testlayer", 0, false, null, minScale, maxScale, bbox);
	}

	@Test
	@DisplayName("a layer without scale limits gets no window of its own")
	void noScaleLimitsMeansNoWindow() {
		assertThat(MapLayerService.zoomRangeOf(layer(null, null, HAMBURG))).isNull();
	}

	@Test
	@DisplayName("Hamburg's latitude is worth a whole zoom level and must not be ignored")
	void latitudeShiftsTheWindow() {
		// m2500_farbig declares MaxScaleDenominator 3000. At the equator that is zoom
		// 17.51; at Hamburg's 53.7 degrees it is 16.75. Ignoring the difference hid the
		// layer across two levels the service does draw.
		int[] hamburg = MapLayerService.zoomRangeOf(layer(null, 3000.0, HAMBURG));
		int[] equator = MapLayerService.zoomRangeOf(layer(null, 3000.0, new double[] { -1, -1, 1, 1 }));

		assertThat(hamburg[0]).isEqualTo(16);
		assertThat(equator[0]).isEqualTo(17);
	}

	@Test
	@DisplayName("the lower bound rounds down, so the level where drawing starts is not cut off")
	void lowerBoundRoundsDown() {
		// 3000 lands on 16.75 in Hamburg. Rounding to nearest would give 17 and leave the
		// whole band from 16.0 up blank even though the service answers there. One
		// transparent tile is cheaper than a layer that looks broken.
		assertThat(MapLayerService.zoomRangeOf(layer(null, 3000.0, HAMBURG))[0]).isEqualTo(16);
		// 7000 -> 15.53, which nearest-rounding would push to 16.
		assertThat(MapLayerService.zoomRangeOf(layer(null, 7000.0, HAMBURG))[0]).isEqualTo(15);
	}

	@Test
	@DisplayName("the upper bound rounds up, mirroring the lower one")
	void upperBoundRoundsUp() {
		// MinScaleDenominator 3000 is the zoomed-in edge: 16.75 rounds up to 17.
		assertThat(MapLayerService.zoomRangeOf(layer(3000.0, null, HAMBURG))[1]).isEqualTo(17);
	}

	@Test
	@DisplayName("a band narrower than one zoom level collapses instead of inverting")
	void narrowBandCollapses() {
		// Measured on m100000_farbig: 75000 to 125000 is a factor of 1.67, less than the
		// factor of two between two adjacent levels. An inverted range would fail the
		// layer_zoom_range CHECK outright.
		int[] range = MapLayerService.zoomRangeOf(layer(75_000.0, 125_000.0, HAMBURG));

		assertThat(range[0]).isLessThanOrEqualTo(range[1]);
	}

	@Test
	@DisplayName("a layer without a bounding box falls back to the equator rather than failing")
	void missingBboxFallsBack() {
		int[] range = MapLayerService.zoomRangeOf(layer(null, 3000.0, null));

		assertThat(range[0]).isEqualTo(17);
	}
}
