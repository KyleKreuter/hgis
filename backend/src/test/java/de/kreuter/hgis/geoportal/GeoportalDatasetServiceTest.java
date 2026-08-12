package de.kreuter.hgis.geoportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
