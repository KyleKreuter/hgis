package de.kreuter.hgis.geoportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.geoportal.dto.GeoportalDtos;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * CONTRACT.md 11.4's {@code description}, which {@link OgcFeaturesClient} resolves from the
 * API landing page rather than the collection endpoint -- confirmed live not to carry it
 * (see {@link OgcFeaturesClient#fetchApiDescription}'s Javadoc). {@link CatalogLoader} is
 * mocked; its own merge logic is {@link CatalogLoaderTest}'s job.
 */
class GeoportalDatasetServiceTest {

	private static final String API_URL = "https://api.hamburg.de/datasets/v1/strassenbaumkataster";
	private static final String COLLECTION = "strassenbaumkataster_hh";
	private static final String DATASET_ID = "strassenbaumkataster/strassenbaumkataster_hh";

	private static GeoportalCatalogEntry oafEntry() {
		return new GeoportalCatalogEntry(DATASET_ID, "Straßenbaumkataster Hamburg", "FEATURES", "BUKEA",
				"Freie und Hansestadt Hamburg, Behörde für Umwelt, Klima, Energie und Agrarwirtschaft", "Umwelt",
				"https://metaver.de/trefferanzeige?docuuid=x", "https://registry.gdi-de.org/id/de.hh/x",
				API_URL, COLLECTION, Map.of());
	}

	/**
	 * A service listed as one row (CONTRACT.md 11.9), shortened to two collections -- the
	 * real {@code xplan} carries 247, and the count is {@code CatalogLoaderTest}'s subject,
	 * not this one's. Titles and ids are the real ones.
	 */
	private static GeoportalCatalogEntry xplanService() {
		String apiUrl = "https://api.hamburg.de/datasets/v1/xplan";
		List<GeoportalCatalogEntry> collections = List.of(
				new GeoportalCatalogEntry("xplan/bp_baugrenze", "BP_BauGrenze", "BOTH", "BSW",
						"Behörde für Stadtentwicklung und Wohnen (BSW)", "Regionen und Städte", null, null,
						apiUrl, "bp_baugrenze", Map.of()),
				new GeoportalCatalogEntry("xplan/bp_baulinie", "BP_BauLinie", "BOTH", "BSW",
						"Behörde für Stadtentwicklung und Wohnen (BSW)", "Regionen und Städte", null, null,
						apiUrl, "bp_baulinie", Map.of()));
		return new GeoportalCatalogEntry("xplan", "XPlanungsdaten Hamburg", "BOTH", "BSW",
				"Behörde für Stadtentwicklung und Wohnen (BSW)", "Regionen und Städte", null, null,
				apiUrl, null, Map.of(), collections);
	}

	private static GeoportalDatasetService serviceFor(GeoportalCatalogEntry entry, RestClient restClient) {
		CatalogLoader loader = mock(CatalogLoader.class);
		when(loader.load()).thenReturn(List.of(entry));
		GeoportalCatalogService catalogService = new GeoportalCatalogService(loader);
		return new GeoportalDatasetService(catalogService, new OgcFeaturesClient(restClient));
	}

	@Test
	@DisplayName("description comes from the API landing page, not the collection endpoint (neither carries it live)")
	void detailResolvesDescriptionFromTheApiLandingPage() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

		// Registered in the order GeoportalDatasetService.detail() actually calls them:
		// fetchCollection, then fetchApiDescription, then fetchQueryables.
		server.expect(requestTo(containsString("/collections/" + COLLECTION + "?")))
				.andRespond(withSuccess("""
						{"itemCount":229876,"storageCrs":"http://www.opengis.net/def/crs/EPSG/0/25832"}
						""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(API_URL + "?f=json"))
				.andRespond(withSuccess("""
						{"title":"Straßenbaumkataster Hamburg","description":"Baumstandorte im öffentlichen Raum"}
						""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(containsString("/queryables")))
				.andRespond(withSuccess("""
						{"properties":{"gid":{"title":"gid","type":"integer","x-ogc-role":"id"}}}
						""", MediaType.APPLICATION_JSON));

		GeoportalDtos.DatasetDetail detail = serviceFor(oafEntry(), builder.build()).detail(DATASET_ID);

		assertThat(detail.description()).isEqualTo("Baumstandorte im öffentlichen Raum");
		assertThat(detail.featureCount()).isEqualTo(229876L);
		server.verify();
	}

	@Test
	@DisplayName("a landing page without a description leaves it null, not an empty string or an error")
	void missingDescriptionStaysNull() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

		server.expect(requestTo(containsString("/collections/" + COLLECTION + "?")))
				.andRespond(withSuccess("{\"itemCount\":229876}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(API_URL + "?f=json"))
				.andRespond(withSuccess("{\"title\":\"Straßenbaumkataster Hamburg\"}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(containsString("/queryables")))
				.andRespond(withSuccess("{\"properties\":{}}", MediaType.APPLICATION_JSON));

		GeoportalDtos.DatasetDetail detail = serviceFor(oafEntry(), builder.build()).detail(DATASET_ID);

		assertThat(detail.description()).isNull();
	}

	/**
	 * CONTRACT.md 11.9: a service listed as one row answers with its collections, and with
	 * nothing that describes a single collection -- no field list, no object count, no id
	 * field, because none of its collections is chosen yet. It must also stay silent on the
	 * network: there is no collection to ask about, and asking the landing page anyway would
	 * pay for a request whose answer nothing reads.
	 */
	@Test
	@DisplayName("das Detail eines Dienstes nennt seine Sammlungen und fragt dafür keinen Dienst an")
	void aServiceDetailAnswersWithItsCollectionsAndMakesNoHttpCall() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

		GeoportalDtos.DatasetDetail detail = serviceFor(xplanService(), builder.build()).detail("xplan");

		assertThat(detail.collectionCount()).isEqualTo(2);
		assertThat(detail.collections()).extracting(GeoportalDtos.CollectionRef::id)
				.containsExactly("xplan/bp_baugrenze", "xplan/bp_baulinie");
		assertThat(detail.collections()).extracting(GeoportalDtos.CollectionRef::title)
				.containsExactly("BP_BauGrenze", "BP_BauLinie");
		assertThat(detail.fields()).isEmpty();
		assertThat(detail.featureCount()).isNull();
		assertThat(detail.sourceFeatureIdField()).isNull();
		assertThat(detail.storageSrid()).isNull();
		assertThat(detail.licenseName()).as("the licence holds for the service too").isEqualTo(GeoportalLicense.NAME);
		server.verify();
	}

	/**
	 * A flat entry must say so in the same field, or the client cannot tell the two apart
	 * from a detail it fetched on its own.
	 */
	@Test
	@DisplayName("ein flacher Eintrag meldet genau eine Sammlung und keine Auswahlliste")
	void aFlatDetailReportsOneCollectionAndNoChoice() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

		server.expect(requestTo(containsString("/collections/" + COLLECTION + "?")))
				.andRespond(withSuccess("{\"itemCount\":229876}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(API_URL + "?f=json"))
				.andRespond(withSuccess("{\"title\":\"Straßenbaumkataster Hamburg\"}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(containsString("/queryables")))
				.andRespond(withSuccess("{\"properties\":{}}", MediaType.APPLICATION_JSON));

		GeoportalDtos.DatasetDetail detail = serviceFor(oafEntry(), builder.build()).detail(DATASET_ID);

		assertThat(detail.collectionCount()).isEqualTo(1);
		assertThat(detail.collections()).isEmpty();
	}

	/**
	 * CONTRACT.md 11.9: "a service id alone is a 400, since there is nothing to import from
	 * a service as such". The message has to name the way out -- the user is one pick away
	 * from an import that works, unlike the WMS-only case below, which has none.
	 */
	@Test
	@DisplayName("ein Importversuch mit einer Dienstkennung ist ein 400, das zur Sammlungswahl auffordert")
	void importingAServiceIdIsRejectedWithAHintToPickACollection() {
		GeoportalDatasetService service = serviceFor(xplanService(), RestClient.builder().build());

		assertThatThrownBy(() -> service.requireImportable("xplan"))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("XPlanungsdaten Hamburg")
				.hasMessageContaining("Sammlung");
	}

	/** The collection the user picks is importable, and reachable under its own id -- it is never listed. */
	@Test
	@DisplayName("eine im Detail gewählte Sammlung ist unter eigener Kennung auffindbar und importierbar")
	void aCollectionOfAServiceIsFoundByItsOwnIdAndImportable() {
		GeoportalDatasetService service = serviceFor(xplanService(), RestClient.builder().build());

		GeoportalCatalogEntry collection = service.requireImportable("xplan/bp_baulinie");

		assertThat(collection.title()).isEqualTo("BP_BauLinie");
		assertThat(collection.collection()).isEqualTo("bp_baulinie");
		assertThat(collection.hasOgcFeatures()).isTrue();
		assertThat(service.list().datasets()).as("but the listing shows the service, not its collections")
				.extracting(GeoportalDtos.DatasetSummary::id).containsExactly("xplan");
	}

	@Test
	@DisplayName("a WMS-only entry never makes a network call and its description stays null")
	void wmsOnlyEntryMakesNoHttpCall() {
		RestClient.Builder builder = RestClient.builder();
		// No expectations registered on purpose: any HTTP call this path made by mistake
		// would fail the test loudly instead of quietly reaching the real network.
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

		GeoportalCatalogEntry wmsOnly = new GeoportalCatalogEntry(
				"md:x", "ALKIS Flurstücke (gelb)", "WMS", "LGV", "Landesbetrieb Geoinformation und Vermessung (LGV)",
				"Umwelt", "https://metaver.de/trefferanzeige?docuuid=y", null, null, null, Map.of());

		GeoportalDtos.DatasetDetail detail = serviceFor(wmsOnly, builder.build()).detail("md:x");

		assertThat(detail.description()).isNull();
		assertThat(detail.featureCount()).isNull();
		server.verify();
	}
}
