package de.kreuter.hgis.basemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The catalog data itself, independent of the two endpoints that read it -- a plain unit
 * test, no {@code @SpringBootTest}, since {@link BasemapCatalog} is a static holder, not
 * a bean.
 */
class BasemapCatalogTest {

	/**
	 * The five ids that predate this catalog (added as an enum 27.08., moved here the
	 * same day): twelve of the user's existing projects store one of these as plain text
	 * in their {@code basemap} column. Renaming any of them silently breaks that
	 * project's map the next time it opens.
	 */
	@Test
	void keepsTheFiveOriginalIdsAndTheirMeaning() {
		BasemapEntry osm = entry("osm");
		assertThat(osm.urlTemplate()).isEqualTo("https://tile.openstreetmap.org/{z}/{x}/{y}.png");
		assertThat(osm.paint()).isNull();

		BasemapEntry light = entry("osm-light");
		assertThat(light.urlTemplate()).isEqualTo(osm.urlTemplate());
		assertThat(light.paint()).containsEntry("raster-saturation", -0.9)
				.containsEntry("raster-brightness-min", 0.32)
				.containsEntry("raster-contrast", -0.22);

		BasemapEntry dark = entry("osm-dark");
		assertThat(dark.urlTemplate()).isEqualTo(osm.urlTemplate());
		assertThat(dark.paint()).containsEntry("raster-saturation", -0.65)
				.containsEntry("raster-brightness-max", 0.38)
				.containsEntry("raster-contrast", 0.22);

		BasemapEntry opentopo = entry("opentopo");
		assertThat(opentopo.urlTemplate()).isEqualTo("https://a.tile.opentopomap.org/{z}/{x}/{y}.png");

		BasemapEntry none = entry("none");
		assertThat(none.urlTemplate()).isNull();
		assertThat(none.attribution()).isEmpty();
	}

	@Test
	void everyIdIsUnique() {
		List<String> ids = BasemapCatalog.list().stream().map(BasemapEntry::id).toList();
		assertThat(ids).doesNotHaveDuplicates();
	}

	/**
	 * Every entry except {@code "none"} needs a template MapLibre can actually request
	 * tiles from -- either the tile triple (Form A) or {@code {bbox-epsg-3857}} (Form B,
	 * VERTRAG.md "Zwei Formen von urlTemplate", added 27.08. for the WMS-only
	 * Landesdienste).
	 */
	@Test
	void everyEntryExceptNoneHasAValidTemplate() {
		for (BasemapEntry candidate : BasemapCatalog.list()) {
			if (candidate.id().equals("none")) {
				continue;
			}
			String template = candidate.urlTemplate();
			boolean hasTileTriple = template.contains("{z}") && template.contains("{x}") && template.contains("{y}");
			boolean hasBbox = template.contains("{bbox-epsg-3857}");
			assertThat(hasTileTriple || hasBbox)
					.as("urlTemplate of %s has neither the tile triple nor {bbox-epsg-3857}: %s",
							candidate.id(), template)
					.isTrue();
		}
	}

	/**
	 * The Landesdienste {@code recherche} found (basemap-recherche.md,
	 * feature/basemap-recherche), minus Baden-Württemberg (embedded third-party
	 * credentials, unverified) and Sachsen-Anhalt (no license text found at all) -- both
	 * deliberately left out, see the comment above these entries in {@link
	 * BasemapCatalog}.
	 */
	@Test
	void hasTheEighteenLandesdiensteFromTheResearch() {
		Set<String> stateIds = Set.of(
				"hh-geobasiskarten-farbig", "hh-dop-unbelaubt", "hh-dop-belaubt",
				"bb-dop20c", "bb-webatlasde-halbton",
				"nw-dop",
				"by-webkarte", "by-webkarte-grau", "by-dop", "by-dop-cir",
				"be-truedop", "ni-dop20", "sh-dop20", "sn-dop", "th-dop", "hb-dop20", "he-dop", "mv-dop");
		assertThat(stateIds).hasSize(18);
		for (String id : stateIds) {
			assertThat(BasemapCatalog.isKnownId(id)).as("catalog knows %s", id).isTrue();
		}
	}

	@Test
	void excludesBadenWuerttembergAndSachsenAnhalt() {
		assertThat(BasemapCatalog.isKnownId("bw-basiskarte")).isFalse();
		assertThat(BasemapCatalog.isKnownId("st-dop20")).isFalse();
		assertThat(BasemapCatalog.list().stream().map(BasemapEntry::coverage))
				.doesNotContain(BasemapCatalog.COVERAGE_BW, BasemapCatalog.COVERAGE_ST);
	}

	/**
	 * The four Fall-2 states (own tile grid, not usable as a template) that {@code
	 * recherche} found a WMS twin for instead -- their WMTS-only siblings never made it
	 * into the catalog, only the WMS entry did.
	 */
	@Test
	void theFourFall2StatesUseTheirWmsTwinInstead() {
		for (String id : new String[] { "bb-dop20c", "nw-dop", "sn-dop", "mv-dop" }) {
			assertThat(entry(id).urlTemplate()).as(id).contains("{bbox-epsg-3857}");
		}
	}

	@Test
	void catalogHasFortyNineEntries() {
		assertThat(BasemapCatalog.list()).hasSize(49);
	}

	/** Only the nine Esri layers require an ArcGIS account. */
	@Test
	void exactlyTheNineEsriLayersRequireAnAccount() {
		Set<String> flagged = BasemapCatalog.list().stream()
				.filter(BasemapEntry::requiresAccount)
				.map(BasemapEntry::id)
				.collect(Collectors.toSet());
		assertThat(flagged).hasSize(9).allMatch(id -> id.startsWith("esri-"));
	}

	@Test
	void requireValidAcceptsEveryCatalogId() {
		for (BasemapEntry candidate : BasemapCatalog.list()) {
			BasemapCatalog.requireValid(candidate.id());
		}
	}

	@Test
	void requireValidRejectsAnUnknownToken() {
		assertThatThrownBy(() -> BasemapCatalog.requireValid("grayscale"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageStartingWith("Unbekannte Hintergrundkarte: grayscale.");
	}

	@Test
	void requireValidDelegatesAnHttpsValueToTheUrlTemplateCheck() {
		BasemapCatalog.requireValid("https://tiles.example.org/{z}/{x}/{y}.png");
		BasemapCatalog.requireValid("https://geodienste.hamburg.de/wms?BBOX={bbox-epsg-3857}");

		assertThatThrownBy(() -> BasemapCatalog.requireValid("https://tiles.example.org/fixed.png"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Die URL-Vorlage muss entweder {z}, {x} und {y} oder {bbox-epsg-3857} enthalten.");
	}

	private static BasemapEntry entry(String id) {
		return BasemapCatalog.list().stream()
				.filter(candidate -> candidate.id().equals(id))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no catalog entry " + id));
	}
}
