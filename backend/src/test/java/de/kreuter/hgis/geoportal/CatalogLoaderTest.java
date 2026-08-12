package de.kreuter.hgis.geoportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The merge logic described in {@link CatalogLoader}'s own Javadoc, checked against
 * fixtures shaped like the two real files (structure and one BOM-prefixed header, quoted
 * commas and all -- measured live, see the phase 23 backend report). No test here touches
 * the network.
 */
class CatalogLoaderTest {

	private static final String SERVICE_DIRECTORY = """
			[
			  {"typ":"OAF","name":"Straßenbaumkataster Hamburg",
			   "url":"https://api.hamburg.de/datasets/v1/strassenbaumkataster",
			   "collection":"strassenbaumkataster_hh",
			   "gfiAttributes":{"gattung":"Gattung"},
			   "datasets":[{"md_id":"C1C61928-C602-4E37-AF31-2D23901E2540",
			                "rs_id":"https://registry.gdi-de.org/id/de.hh/C1C61928-C602-4E37-AF31-2D23901E2540",
			                "kategorie_organisation":"Behörde für Umwelt, Klima, Energie und Agrarwirtschaft (BUKEA)"}]},
			  {"typ":"WMS","name":"Irrelevant WMS entry","url":"https://geodienste.hamburg.de/HH_WMS_X"},
			  {"typ":"OAF","name":"Zweite Sammlung derselben API",
			   "url":"https://api.hamburg.de/datasets/v1/strassenbaumkataster/",
			   "collection":"strassenbaumkataster_zweite","datasets":[]}
			]
			""";

	/** Header BOM-prefixed, a quoted field with an embedded comma -- both measured live. */
	private static final String DATASET_LIST = "﻿"
			+ "Datensatzname,Organisation,Kategorie,Metadaten,Portal,WMS-Adresse,WFS-Adresse,OAF-Landing Page,Aufrufbar\n"
			+ "Straßenbaumkataster Hamburg,\"Behörde für Umwelt, Klima, Energie und Agrarwirtschaft (BUKEA)\",Umwelt,"
			+ "https://metaver.de/trefferanzeige?docuuid=C1C61928-C602-4E37-AF31-2D23901E2540,"
			+ "https://geoportal-hamburg.de/x,"
			+ "https://geodienste.hamburg.de/HH_WMS_Strassenbaumkataster?SERVICE=WMS,"
			+ "https://geodienste.hamburg.de/HH_WFS_Strassenbaumkataster?SERVICE=WFS,"
			+ "https://api.hamburg.de/datasets/v1/strassenbaumkataster,FHHNET/Internet\n"
			+ "ALKIS Flurstücke (gelb),\"Landesbetrieb Geoinformation und Vermessung (LGV)\",Umwelt,"
			+ "https://metaver.de/trefferanzeige?docuuid=F691CFB0-D38F-4308-B12F-1671166FF181,"
			+ "https://geoportal-hamburg.de/y,"
			+ "https://geodienste.hamburg.de/HH_WMS_Fachdaten_ALKIS?SERVICE=WMS,,,FHHNET/Internet\n"
			+ "Geheimer Datensatz,Behörde X,Verkehr,,,https://geodienste.hamburg.de/HH_WMS_Geheim?SERVICE=WMS,,,nur FHHNET\n";

	private List<GeoportalCatalogEntry> load() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://geodienste.hamburg.de/services-internet.json"))
				.andRespond(withSuccess(SERVICE_DIRECTORY, MediaType.APPLICATION_JSON));
		server.expect(requestTo("https://geoportal-hamburg.de/urbandataplatform/datasets.csv"))
				.andRespond(withSuccess(DATASET_LIST.getBytes(StandardCharsets.UTF_8), MediaType.valueOf("text/csv")));

		CatalogLoader loader = new CatalogLoader(builder.build());
		return loader.load();
	}

	@Test
	@DisplayName("only rows reachable from the internet become catalog entries (plan section 3.5)")
	void onlyPubliclyReachableRowsSurvive() {
		List<GeoportalCatalogEntry> entries = load();

		assertThat(entries).extracting(GeoportalCatalogEntry::title)
				.containsExactlyInAnyOrder("Straßenbaumkataster Hamburg", "ALKIS Flurstücke (gelb)");
	}

	@Test
	@DisplayName("a row with an OAF binding gets kind BOTH (WMS and an object service both present) and the api/collection id")
	void oafRowGetsBoundToTheServiceDirectoryEntry() {
		GeoportalCatalogEntry entry = load().stream()
				.filter(e -> e.title().equals("Straßenbaumkataster Hamburg"))
				.findFirst().orElseThrow();

		assertThat(entry.id()).isEqualTo("strassenbaumkataster/strassenbaumkataster_hh");
		assertThat(entry.kind()).isEqualTo("BOTH");
		assertThat(entry.apiUrl()).isEqualTo("https://api.hamburg.de/datasets/v1/strassenbaumkataster");
		assertThat(entry.collection()).isEqualTo("strassenbaumkataster_hh");
		assertThat(entry.datasetUri()).isEqualTo("https://registry.gdi-de.org/id/de.hh/C1C61928-C602-4E37-AF31-2D23901E2540");
		assertThat(entry.metadataUrl()).isEqualTo("https://metaver.de/trefferanzeige?docuuid=C1C61928-C602-4E37-AF31-2D23901E2540");
		assertThat(entry.gfiAttributes()).isEqualTo(Map.of("gattung", "Gattung"));
		assertThat(entry.hasOgcFeatures()).isTrue();
	}

	@Test
	@DisplayName("a second collection under the same API landing page never overrides the first (documented simplification)")
	void firstCollectionPerApiUrlWins() {
		GeoportalCatalogEntry entry = load().stream()
				.filter(e -> e.title().equals("Straßenbaumkataster Hamburg"))
				.findFirst().orElseThrow();

		assertThat(entry.collection()).isEqualTo("strassenbaumkataster_hh");
	}

	@Test
	@DisplayName("the short agency form is the parenthesised abbreviation; attribution keeps the full organisation name")
	void agencyIsAbbreviatedAttributionIsNot() {
		GeoportalCatalogEntry entry = load().stream()
				.filter(e -> e.title().equals("Straßenbaumkataster Hamburg"))
				.findFirst().orElseThrow();

		assertThat(entry.agency()).isEqualTo("BUKEA");
		assertThat(entry.attribution())
				.isEqualTo("Behörde für Umwelt, Klima, Energie und Agrarwirtschaft (BUKEA)");
	}

	@Test
	@DisplayName("a row with only a WMS address and no OAF binding still becomes an entry, kind WMS, id falls back to its metadata uuid")
	void wmsOnlyRowHasNoOgcFeaturesBinding() {
		GeoportalCatalogEntry entry = load().stream()
				.filter(e -> e.title().equals("ALKIS Flurstücke (gelb)"))
				.findFirst().orElseThrow();

		assertThat(entry.kind()).isEqualTo("WMS");
		assertThat(entry.hasOgcFeatures()).isFalse();
		assertThat(entry.apiUrl()).isNull();
		assertThat(entry.id()).isEqualTo("md:f691cfb0-d38f-4308-b12f-1671166ff181");
		assertThat(entry.agency()).isEqualTo("LGV");
	}
}
