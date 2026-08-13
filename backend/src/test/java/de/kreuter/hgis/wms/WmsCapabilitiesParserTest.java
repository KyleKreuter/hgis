package de.kreuter.hgis.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import de.kreuter.hgis.common.UnprocessableEntityException;
import de.kreuter.hgis.wms.dto.WmsDtos;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checked against four real GetCapabilities documents, saved as fixtures rather than
 * invented -- CONTRACT.md names all four, each for what it alone exercises:
 *
 * <ul>
 * <li>{@code HH_WMS_Cache_Stadtplan} -- one layer, itself the outermost {@code <Layer>}</li>
 * <li>{@code HH_WMS_Geobasiskarten} -- a named container with named, scale-limited children</li>
 * <li>{@code HH_WMS_Fachdaten_ALKIS} -- several levels of unnamed grouping layers</li>
 * <li>{@code HH_WMS_Cache_Rasterplan} -- the one Hamburg service, of 40 sampled, that
 * cannot serve EPSG:3857</li>
 * </ul>
 *
 * No test here touches the network; every document was fetched once while building this
 * class and is read from {@code src/test/resources/wms} from then on.
 */
class WmsCapabilitiesParserTest {

	private static byte[] fixture(String name) {
		try (InputStream in = WmsCapabilitiesParserTest.class.getResourceAsStream("/wms/" + name)) {
			if (in == null) {
				throw new IllegalStateException("Test fixture missing: " + name);
			}
			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// --- HH_WMS_Cache_Stadtplan: one layer, itself the outermost <Layer> -----------------

	@Test
	@DisplayName("a single-layer service lists that one layer, at depth 0")
	void singleLayerServiceListsItself() {
		WmsDtos.CapabilitiesResponse response =
				WmsCapabilitiesParser.parse(fixture("HH_WMS_Cache_Stadtplan.xml"), "https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan");

		assertThat(response.serviceUrl()).isEqualTo("https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan");
		assertThat(response.title()).isEqualTo("WMS Cache Stadtplan Hamburg");
		assertThat(response.version()).isEqualTo("1.3.0");
		assertThat(response.imageFormats()).containsExactly(
				"image/png", "image/jpeg", "image/gif", "image/GeoTIFF", "image/tiff");

		assertThat(response.layers()).hasSize(1);
		WmsDtos.Layer layer = response.layers().get(0);
		assertThat(layer.name()).isEqualTo("stadtplan");
		assertThat(layer.title()).isEqualTo("Stadtplan");
		assertThat(layer.depth()).isZero();
		assertThat(layer.queryable()).isFalse();
		assertThat(layer.minScale()).isNull();
		assertThat(layer.maxScale()).isNull();
		assertThat(layer.legendUrl()).isEqualTo("https://geodienste.hamburg.de/HH_WMS_Cache_Stadtplan?"
				+ "format=image%2Fpng&layer=stadtplan&sld_version=1.1.0&request=GetLegendGraphic&service=WMS&version=1.1.1&styles=");
		assertThat(layer.bbox()).containsExactly(
				new double[] { 7.854857391112174, 52.55556353982825, 12.447294191950709, 54.553776875452414 },
				within(1e-9));
	}

	// --- HH_WMS_Geobasiskarten: a named container with named, scale-limited children ----

	@Test
	@DisplayName("a named container with named children lists the children, not itself, and their scale limits")
	void namedContainerListsItsChildrenNotItself() {
		WmsDtos.CapabilitiesResponse response = WmsCapabilitiesParser.parse(
				fixture("HH_WMS_Geobasiskarten.xml"), "https://geodienste.hamburg.de/HH_WMS_Geobasiskarten");

		assertThat(response.title()).isEqualTo("WMS Geobasiskarten Hamburg (farbig)");
		// The outermost <Layer> itself (wms_geobasiskarten_n) is named and has children,
		// so it is excluded from the list -- only its nine descendants appear.
		assertThat(response.layers()).extracting(WmsDtos.Layer::name).containsExactly(
				"geobasiskarten_farbig", "m2500_farbig", "m5000_farbig", "m10000_farbig", "m20000_farbig",
				"m40000_farbig", "m60000_farbig", "m100000_farbig", "m125000_farbig");

		WmsDtos.Layer group = response.layers().get(0);
		assertThat(group.title()).isEqualTo("Geobasiskarten (farbig)");
		assertThat(group.depth()).isZero();
		assertThat(group.legendUrl()).as("the group has its own Style/LegendURL").isNotNull();

		WmsDtos.Layer m2500 = response.layers().get(1);
		assertThat(m2500.depth()).as("nested one level inside the named group").isEqualTo(1);
		assertThat(m2500.minScale()).as("no MinScaleDenominator of its own, and nothing above it declares one").isNull();
		assertThat(m2500.maxScale()).isEqualTo(3000.0);
		assertThat(m2500.legendUrl()).as("legend is not inherited from the group").isNull();

		WmsDtos.Layer m5000 = response.layers().get(2);
		assertThat(m5000.minScale()).isEqualTo(3000.0);
		assertThat(m5000.maxScale()).isEqualTo(7000.0);
	}

	// --- HH_WMS_Fachdaten_ALKIS: several levels of unnamed grouping layers --------------

	@Test
	@DisplayName("unnamed grouping layers still count toward their children's depth")
	void unnamedGroupsStillNestTheirChildren() {
		WmsDtos.CapabilitiesResponse response = WmsCapabilitiesParser.parse(
				fixture("HH_WMS_Fachdaten_ALKIS.xml"), "https://geodienste.hamburg.de/HH_WMS_Fachdaten_ALKIS");

		// "8" sits directly under the (unnamed) root's direct child -- no, it sits as a
		// direct child of the outermost <Layer>, one level below an unnamed sibling group,
		// giving it depth 0; "1" sits inside an unnamed group one level further in, depth 1;
		// "24" sits three unnamed/named boundaries deep, depth 2 -- and "28", inside one
		// more unnamed group below that, is the deepest entry in the fixture, depth 3.
		assertThat(entryNamed(response, "8").depth()).isZero();
		assertThat(entryNamed(response, "1").depth()).isEqualTo(1);
		assertThat(entryNamed(response, "24").depth()).isEqualTo(2);
		assertThat(entryNamed(response, "28").depth()).isEqualTo(3);

		// Scale and bbox inherit down through the unnamed groups the same as through a
		// named one -- "28"'s own MaxScaleDenominator (2456.845238) comes from its direct,
		// unnamed parent, not from "24"'s sibling group two levels up (9449.404762).
		assertThat(entryNamed(response, "28").maxScale()).isEqualTo(2456.845238);
		assertThat(entryNamed(response, "28").queryable()).isFalse();
		assertThat(entryNamed(response, "24").queryable()).isTrue();
	}

	/**
	 * Orchestrator finding: hiding an unnamed group entirely made its named children
	 * read as if they belonged to whichever named entry happened to sit above them in
	 * the flattened list. "Nacht-Schutzzone", "Tag-Schutzzone 2" and "Tag-Schutzzone 1"
	 * are not children of "vorbereitende Untersuchung..." two entries above them -- their
	 * real, unnamed parent is "Laermschutzbereiche", the one title that actually explains
	 * what a "Nacht-Schutzzone" is.
	 */
	@Test
	@DisplayName("an unnamed group with children is listed with name null, as a heading for what follows it")
	void anUnnamedGroupWithChildrenIsListedAsAHeading() {
		WmsDtos.CapabilitiesResponse response = WmsCapabilitiesParser.parse(
				fixture("HH_WMS_Fachdaten_ALKIS.xml"), "https://geodienste.hamburg.de/HH_WMS_Fachdaten_ALKIS");

		WmsDtos.Layer heading = entryTitled(response, "Laermschutzbereiche");
		assertThat(heading.name()).isNull();
		assertThat(heading.depth()).isZero();
		assertThat(heading.queryable()).isFalse();
		assertThat(heading.legendUrl()).isNull();

		// Its children keep the depth they already had -- listing the group changes
		// nothing about where its children sit, only what explains them.
		WmsDtos.Layer firstChild = entryNamed(response, "1");
		assertThat(firstChild.title()).isEqualTo("Nacht-Schutzzone");
		assertThat(firstChild.depth()).isEqualTo(1);

		// The heading comes right before its children in document order.
		int headingIndex = response.layers().indexOf(heading);
		int firstChildIndex = response.layers().indexOf(firstChild);
		assertThat(firstChildIndex).isEqualTo(headingIndex + 1);
	}

	@Test
	@DisplayName("every unnamed group with children is listed, every unnamed leaf is not")
	void exactlySixteenUnnamedGroupsAreListedInThisFixture() {
		WmsDtos.CapabilitiesResponse response = WmsCapabilitiesParser.parse(
				fixture("HH_WMS_Fachdaten_ALKIS.xml"), "https://geodienste.hamburg.de/HH_WMS_Fachdaten_ALKIS");

		long unnamed = response.layers().stream().filter(layer -> layer.name() == null).count();
		long named = response.layers().stream().filter(layer -> layer.name() != null).count();

		assertThat(unnamed).as("every unnamed grouping layer that has children").isEqualTo(16);
		assertThat(named).as("the pickable layers, unchanged by this addition").isEqualTo(32);
		assertThat(response.layers()).hasSize(48);
	}

	/**
	 * {@code name.equals(l.name())}, not the other way round: the list now also holds
	 * entries whose own {@code name()} is null (unnamed groups with children), and
	 * {@code null.equals(name)} would throw before a real match is ever found.
	 */
	private static WmsDtos.Layer entryNamed(WmsDtos.CapabilitiesResponse response, String name) {
		return response.layers().stream().filter(l -> name.equals(l.name())).findFirst()
				.orElseThrow(() -> new AssertionError("no layer named " + name));
	}

	private static WmsDtos.Layer entryTitled(WmsDtos.CapabilitiesResponse response, String title) {
		return response.layers().stream().filter(l -> title.equals(l.title())).findFirst()
				.orElseThrow(() -> new AssertionError("no layer titled " + title));
	}

	// --- HH_WMS_Cache_Rasterplan: cannot serve EPSG:3857 --------------------------------

	@Test
	@DisplayName("a service whose root layer never names EPSG:3857 is rejected with 422")
	void aServiceWithoutWebMercatorIsRejected() {
		byte[] xml = fixture("HH_WMS_Cache_Rasterplan.xml");

		assertThatThrownBy(() -> WmsCapabilitiesParser.parse(xml, "https://geodienste.hamburg.de/HH_WMS_Cache_Rasterplan"))
				.isInstanceOf(UnprocessableEntityException.class)
				.hasMessageContaining("EPSG:3857");
	}

	// --- version handling ----------------------------------------------------------------

	@Test
	@DisplayName("a WMS 1.1.1 document is rejected with 422, naming the actual version")
	void aDifferentVersionIsRejected() {
		String doc = """
				<?xml version="1.0"?>
				<WMT_MS_Capabilities version="1.1.1">
				  <Service><Title>Alter Dienst</Title></Service>
				  <Capability>
				    <Request><GetMap><Format>image/png</Format></GetMap></Request>
				    <Layer><Name>alt</Name><SRS>EPSG:3857</SRS></Layer>
				  </Capability>
				</WMT_MS_Capabilities>
				""";

		assertThatThrownBy(() -> WmsCapabilitiesParser.parse(doc.getBytes(StandardCharsets.UTF_8), "https://example.test/wms"))
				.isInstanceOf(UnprocessableEntityException.class)
				.hasMessageContaining("1.1.1")
				.hasMessageContaining("1.3.0");
	}

	@Test
	@DisplayName("a document that is not XML at all is a service failure, not a parser crash")
	void unparsableBodyIsReportedAsServiceFailure() {
		byte[] notXml = "<html>not a capabilities document".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> WmsCapabilitiesParser.parse(notXml, "https://example.test/wms"))
				.isInstanceOf(WmsUnavailableException.class);
	}

	@Test
	@DisplayName("a DOCTYPE declaration is refused outright rather than expanded (XXE hardening)")
	void doctypeDeclarationIsRefused() {
		String doc = """
				<?xml version="1.0"?>
				<!DOCTYPE WMS_Capabilities [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
				<WMS_Capabilities version="1.3.0">
				  <Service><Title>&xxe;</Title></Service>
				  <Capability><Layer><Name>x</Name><CRS>EPSG:3857</CRS></Layer></Capability>
				</WMS_Capabilities>
				""";

		assertThatThrownBy(() -> WmsCapabilitiesParser.parse(doc.getBytes(StandardCharsets.UTF_8), "https://example.test/wms"))
				.isInstanceOf(WmsUnavailableException.class);
	}

	// --- small, hand-written documents for edges the real fixtures do not carry --------

	@Test
	@DisplayName("a layer without a Title falls back to its Name")
	void layerWithoutTitleFallsBackToName() {
		String doc = """
				<?xml version="1.0"?>
				<WMS_Capabilities version="1.3.0">
				  <Service><Title>Testdienst</Title></Service>
				  <Capability>
				    <Request><GetMap><Format>image/png</Format></GetMap></Request>
				    <Layer>
				      <CRS>EPSG:3857</CRS>
				      <Layer><Name>ohne_titel</Name><CRS>EPSG:3857</CRS></Layer>
				    </Layer>
				  </Capability>
				</WMS_Capabilities>
				""";

		WmsDtos.CapabilitiesResponse response =
				WmsCapabilitiesParser.parse(doc.getBytes(StandardCharsets.UTF_8), "https://example.test/wms");

		assertThat(response.layers()).extracting(WmsDtos.Layer::name, WmsDtos.Layer::title)
				.containsExactly(tuple("ohne_titel", "ohne_titel"));
	}

	@Test
	@DisplayName("a CRS value is matched case-insensitively against EPSG:3857")
	void crsMatchIsCaseInsensitive() {
		String doc = """
				<?xml version="1.0"?>
				<WMS_Capabilities version="1.3.0">
				  <Service><Title>Testdienst</Title></Service>
				  <Capability>
				    <Request><GetMap><Format>image/png</Format></GetMap></Request>
				    <Layer><Name>x</Name><CRS>epsg:3857</CRS></Layer>
				  </Capability>
				</WMS_Capabilities>
				""";

		WmsDtos.CapabilitiesResponse response =
				WmsCapabilitiesParser.parse(doc.getBytes(StandardCharsets.UTF_8), "https://example.test/wms");

		assertThat(response.layers()).hasSize(1);
	}
}
