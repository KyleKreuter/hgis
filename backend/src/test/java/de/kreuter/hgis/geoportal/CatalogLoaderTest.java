package de.kreuter.hgis.geoportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The merge logic described in {@link CatalogLoader}'s own Javadoc, checked against
 * fixtures cut from the two real files: every service, collection, title, organisation and
 * URL below is copied out of {@code services-internet.json} and {@code datasets.csv} as
 * they were measured, structure and BOM-prefixed header and quoted commas and all. No test
 * here touches the network.
 *
 * <p>Copied rather than invented on purpose. A fixture written from memory agrees with
 * whatever the writer expected -- these two files disagree with that in ways worth having
 * under test: an organisation with no parenthesised abbreviation (231 of the 511 public
 * rows), a {@code gfiAttributes} that is the string {@code "ignore"} instead of an object,
 * a service the dataset list does not mention at all, five collections sharing a title
 * across two services, and one service carrying more collections than the list will show.
 */
class CatalogLoaderTest {

	private static final String API = "https://api.hamburg.de/datasets/v1/";

	/**
	 * The first 21 collections of the {@code xplan} service, real titles in the directory's
	 * own order -- one more than {@code CatalogLoader}'s threshold, so this fixture stands
	 * for the eight services (247 collections at the largest) that are listed as one row.
	 * Their collection ids are the lower-cased titles, which is true of all 247 of them.
	 */
	private static final List<String> XPLAN_COLLECTIONS = List.of(
			"BP_AbgrabungsFlaeche", "BP_AbstandsFlaeche", "BP_AbstandsMass", "BP_AbweichungVonBaugrenze",
			"BP_AbweichungVonUeberbaubarerGrundstuecksFlaeche", "BP_AnpflanzungBindungErhaltung",
			"BP_AufschuettungsFlaeche", "BP_AusgleichsFlaeche", "BP_AusgleichsMassnahme", "BP_BauGrenze",
			"BP_BauLinie", "BP_Baugebiet", "BP_BaugebietsTeilFlaeche", "BP_Bereich",
			"BP_BereichOhneEinAusfahrtLinie", "BP_BesondererNutzungszweckFlaeche", "BP_BodenschaetzeFlaeche",
			"BP_DenkmalschutzEinzelanlage", "BP_DenkmalschutzEnsembleFlaeche", "BP_EinfahrtPunkt",
			"BP_EinfahrtsbereichLinie");

	private static final String FEUCHT_SERVICE = "Biotopverbund der Feuchtlebensräume – nicht abgestimmte Fachgrundlage – Hamburg";
	private static final String TROCKEN_SERVICE = "Biotopverbund der Trockenlebensräume – nicht abgestimmte Fachgrundlage – Hamburg";

	/**
	 * Five services: two whose collections repeat each other's titles, one ordinary single
	 * collection, one the dataset list never mentions, and {@code xplan} above the threshold.
	 * The two WMS entries are there to be skipped -- the real directory holds 2888 of them.
	 */
	private static final String SERVICE_DIRECTORY = ("["
			+ biotopverbund("feuchtlebensraeume", FEUCHT_SERVICE, "27e4611d-85ff-4cad-a6b3-89a37a475ba6") + ","
			+ """
			{"typ":"WMS","name":"Biotopverbund Feuchtlebensräume","url":"https://geodienste.hamburg.de/wms_biotopverbund_feuchtlebensraeume"},
			"""
			+ biotopverbund("trockenlebensraeume", TROCKEN_SERVICE, "99b64456-b738-4fa2-aa45-c316929b6d72") + ","
			+ """
			{"typ":"OAF","name":"Straßenbaumkataster Hamburg",
			 "url":"https://api.hamburg.de/datasets/v1/strassenbaumkataster/",
			 "collection":"strassenbaumkataster_hh",
			 "gfiAttributes":{"baumnummer":"Baumnummer","gattung":"Gattung","art":"Baumart"},
			 "datasets":[{"md_id":"C1C61928-C602-4E37-AF31-2D23901E2540",
			              "md_name":"Straßenbaumkataster Hamburg",
			              "rs_id":"https://registry.gdi-de.org/id/de.hh/C1C61928-C602-4E37-AF31-2D23901E2540"}]},
			{"typ":"OAF","name":"3D-Gebäudemodell (LoD2- DE)",
			 "url":"https://api.hamburg.de/datasets/v1/lod2_hamburg",
			 "collection":"3D-Gebäudemodell (LoD2- DE)",
			 "gfiAttributes":{"gmlId":"GML ID","Gebaeudefunktion":"Gebäudefunktion"},
			 "datasets":[{"md_name":"3D-Gebäudemodell LoD2-DE Hamburg",
			              "rs_id":"https://registry.gdi-de.org/id/de.hh/948321ba-e9b2-4290-88c3-8dda2912defa"}]},
			"""
			+ xplan()
			+ "]");

	/**
	 * Header BOM-prefixed, a quoted field with an embedded comma, and rows in the exact
	 * shape the real file carries them.
	 *
	 * <p>The rows differ in the one respect the real data differs in most often: the ALKIS
	 * row's {@code Organisation} carries no parenthesised abbreviation. 231 of the 511 public
	 * rows carry none, and the single most common value among them is {@code Landesbetrieb
	 * Geoinformation und Vermessung} -- so a fixture in which every row is abbreviated leaves
	 * the branch that handles almost half the catalog untested, and its {@code agency}
	 * silently wrong for all of them.
	 */
	private static final String DATASET_LIST = "﻿"
			+ "Datensatzname,Organisation,Kategorie,Metadaten,Portal,WMS-Adresse,WFS-Adresse,OAF-Landing Page,Aufrufbar\n"
			+ biotopverbundRow("feuchtlebensraeume", FEUCHT_SERVICE, "9C0927DD-F9DC-4C1E-8287-E8773DAC208E")
			+ biotopverbundRow("trockenlebensraeume", TROCKEN_SERVICE, "DA9ADA8E-4A4D-4F71-BD98-95E939378D1C")
			+ "Straßenbaumkataster Hamburg,\"Behörde für Umwelt, Klima, Energie und Agrarwirtschaft (BUKEA)\",Umwelt,"
			+ "https://metaver.de/trefferanzeige?docuuid=C1C61928-C602-4E37-AF31-2D23901E2540,"
			+ "https://geoportal-hamburg.de/Geoportal/geo-online/?mdid=C1C61928-C602-4E37-AF31-2D23901E2540,"
			+ "https://geodienste.hamburg.de/HH_WMS_Strassenbaumkataster?SERVICE=WMS,"
			+ "https://geodienste.hamburg.de/HH_WFS_Strassenbaumkataster?SERVICE=WFS,"
			+ "https://api.hamburg.de/datasets/v1/strassenbaumkataster,FHHNET/Internet\n"
			+ "XPlanungsdaten Hamburg,Behörde für Stadtentwicklung und Wohnen (BSW),Regionen und Städte,"
			+ "https://metaver.de/trefferanzeige?docuuid=D37B0405-6571-4530-ACFC-0710FC80D409,"
			+ "https://geoportal-hamburg.de/Geoportal/geo-online/?mdid=D37B0405-6571-4530-ACFC-0710FC80D409,"
			+ "https://geodienste.hamburg.de/HH_WMS_xplan_pre_planwerke?SERVICE=WMS,"
			+ "https://hh.xplan.diplanung.de/xplansyn-wfs/services/xplansynwfs?SERVICE=WFS,"
			+ "https://api.hamburg.de/datasets/v1/xplan,FHHNET/Internet\n"
			+ "ALKIS - Flurstücke und Gebäude (gelb),Landesbetrieb Geoinformation und Vermessung,Umwelt,"
			+ "https://metaver.de/trefferanzeige?docuuid=F691CFB0-D38F-4308-B12F-1671166FF181,"
			+ "https://geoportal-hamburg.de/Geoportal/geo-online/?mdid=F691CFB0-D38F-4308-B12F-1671166FF181,"
			+ "https://geodienste.hamburg.de/HH_WMS_Fachdaten_ALKIS?SERVICE=WMS,,,FHHNET/Internet\n"
			+ "Baugenehmigungen,Behörde X,Verkehr,,,https://geodienste.hamburg.de/HH_WMS_Geheim?SERVICE=WMS,,,nur FHHNET\n";

	/** One of the two biotope services: five collections, five titles the other one repeats. */
	private static String biotopverbund(String kind, String serviceName, String uuid) {
		String url = API + "biotopverbund_" + kind;
		List<String> collections = List.of("hauptverbundachsen|Hauptverbundachsen", "kernflaechen|Kernflächen",
				"laenderuebergreifende_anknuepfungspunkte|Länderübergreifende Anknüpfungspunkte",
				"verbindungsraeume|Verbindungsräume",
				"verbindungsflaechen_und_elemente|Verbindungsflächen und –elemente");
		// gfiAttributes is the string "ignore" here, not an object -- the real entries say so.
		return collections.stream()
				.map(collection -> collection.split("\\|"))
				.map(collection -> """
						{"typ":"OAF","name":"%s","url":"%s","collection":"%s","gfiAttributes":"ignore",
						 "datasets":[{"md_name":"%s","rs_id":"https://registry.gdi-de.org/id/de.hh/%s"}]}"""
						.formatted(collection[1], url, collection[0], serviceName, uuid))
				.collect(Collectors.joining(","));
	}

	private static String xplan() {
		return XPLAN_COLLECTIONS.stream()
				.map(title -> """
						{"typ":"OAF","name":"%s","url":"%sxplan","collection":"%s","gfiAttributes":{},
						 "datasets":[{"md_name":"XPlanungsdaten Hamburg",
						              "rs_id":"https://registry.gdi-de.org/id/de.hh/d247341c-66e6-40fe-96dd-370b141ac473"}]}"""
						.formatted(title, API, title.toLowerCase(Locale.ROOT)))
				.collect(Collectors.joining(","));
	}

	/** @param kind the technical half of the service id, e.g. {@code feuchtlebensraeume} */
	private static String biotopverbundRow(String kind, String serviceName, String uuid) {
		return serviceName + ",\"Behörde für Umwelt, Klima, Energie und Agrarwirtschaft (BUKEA)\",Umwelt,"
				+ "https://metaver.de/trefferanzeige?docuuid=" + uuid + ","
				+ "https://geoportal-hamburg.de/Geoportal/geo-online/?mdid=" + uuid + ","
				+ "https://geodienste.hamburg.de/wms_biotopverbund_" + kind + "?SERVICE=WMS,"
				+ "https://geodienste.hamburg.de/wfs_biotopverbund_" + kind + "?SERVICE=WFS,"
				+ API + "biotopverbund_" + kind + ",FHHNET/Internet\n";
	}

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

	private static GeoportalCatalogEntry byId(List<GeoportalCatalogEntry> entries, String id) {
		return entries.stream().filter(entry -> entry.id().equals(id)).findFirst()
				.orElseThrow(() -> new AssertionError("no entry " + id + " among " + entries.size() + " entries"));
	}

	// --- one entry per collection --------------------------------------------------------

	/**
	 * The whole point of CONTRACT.md 11.9. The dataset list names {@code biotopverbund_
	 * feuchtlebensraeume} once; the service directory names five collections under it, and
	 * all five have to reach the catalog. Under the old join, four of them could not be
	 * imported at all -- 1164 collections were in that position.
	 */
	@Test
	@DisplayName("jede Sammlung eines Dienstes wird ein eigener Katalogeintrag")
	void everyCollectionOfAServiceBecomesItsOwnEntry() {
		List<GeoportalCatalogEntry> entries = load();

		assertThat(entries).filteredOn(entry -> "biotopverbund_feuchtlebensraeume".equals(
						entry.id().split("/")[0]))
				.extracting(GeoportalCatalogEntry::id)
				.containsExactly(
						"biotopverbund_feuchtlebensraeume/hauptverbundachsen",
						"biotopverbund_feuchtlebensraeume/kernflaechen",
						"biotopverbund_feuchtlebensraeume/laenderuebergreifende_anknuepfungspunkte",
						"biotopverbund_feuchtlebensraeume/verbindungsraeume",
						"biotopverbund_feuchtlebensraeume/verbindungsflaechen_und_elemente");
	}

	@Test
	@DisplayName("ein flacher Eintrag nennt genau eine Sammlung und ist importierbar")
	void aFlatEntryNamesExactlyOneCollection() {
		GeoportalCatalogEntry entry = byId(load(), "strassenbaumkataster/strassenbaumkataster_hh");

		assertThat(entry.title()).isEqualTo("Straßenbaumkataster Hamburg");
		assertThat(entry.collectionCount()).isEqualTo(1);
		assertThat(entry.isService()).isFalse();
		assertThat(entry.hasOgcFeatures()).isTrue();
		// The trailing slash the directory carries on this one URL must not survive into the
		// id, or the same service would be two services.
		assertThat(entry.apiUrl()).isEqualTo("https://api.hamburg.de/datasets/v1/strassenbaumkataster");
		assertThat(entry.collection()).isEqualTo("strassenbaumkataster_hh");
		assertThat(entry.gfiAttributes()).containsEntry("gattung", "Gattung").hasSize(3);
	}

	/**
	 * The labels are per collection, not per service: two collections of one service
	 * describe different objects and the dialog must not offer one's field names for the
	 * other. A {@code gfiAttributes} that is the string {@code "ignore"} -- which is what
	 * both biotope services carry -- means no labels, not a broken entry.
	 */
	@Test
	@DisplayName("gfiAttributes ist leer, wenn das Verzeichnis dort keine Zuordnung führt")
	void aCollectionWithoutLabelsGetsAnEmptyMap() {
		GeoportalCatalogEntry entry = byId(load(), "biotopverbund_feuchtlebensraeume/kernflaechen");

		assertThat(entry.gfiAttributes()).isEmpty();
	}

	// --- what the dataset list adds ------------------------------------------------------

	@Test
	@DisplayName("alle Sammlungen eines Dienstes erben Behörde, Thema und Lizenzangaben seiner Zeile")
	void everyCollectionInheritsAgencyTopicAndLicenceFromTheServicesRow() {
		List<GeoportalCatalogEntry> entries = load();

		assertThat(entries).filteredOn(entry -> entry.id().startsWith("biotopverbund_feuchtlebensraeume/"))
				.allSatisfy(entry -> {
					assertThat(entry.agency()).isEqualTo("BUKEA");
					assertThat(entry.attribution())
							.isEqualTo("Behörde für Umwelt, Klima, Energie und Agrarwirtschaft (BUKEA)");
					assertThat(entry.topic()).isEqualTo("Umwelt");
					assertThat(entry.metadataUrl())
							.isEqualTo("https://metaver.de/trefferanzeige?docuuid=9C0927DD-F9DC-4C1E-8287-E8773DAC208E");
					assertThat(entry.datasetUri())
							.isEqualTo("https://registry.gdi-de.org/id/de.hh/27e4611d-85ff-4cad-a6b3-89a37a475ba6");
					// The row carries a WMS address as well as an object service.
					assertThat(entry.kind()).isEqualTo("BOTH");
					assertThat(entry.wmsUrl())
							.isEqualTo("https://geodienste.hamburg.de/wms_biotopverbund_feuchtlebensraeume?SERVICE=WMS");
				});
	}

	/**
	 * Two of the 405 measured services have no row in the dataset list at all. Dropping them
	 * would make the catalog smaller than the directory for no reason the user could see;
	 * they are listed, with the fields only that row could have filled left null, which
	 * CONTRACT.md 11.2 allows for each of them.
	 */
	@Test
	@DisplayName("ein Dienst ohne Zeile in der Datensatzliste bleibt im Katalog, ohne Behörde und Thema")
	void aServiceWithoutADatasetListRowIsStillListed() {
		GeoportalCatalogEntry entry = byId(load(), "lod2_hamburg/3D-Gebäudemodell (LoD2- DE)");

		assertThat(entry.title()).isEqualTo("3D-Gebäudemodell (LoD2- DE)");
		assertThat(entry.agency()).isNull();
		assertThat(entry.attribution()).isNull();
		assertThat(entry.topic()).isNull();
		assertThat(entry.metadataUrl()).isNull();
		assertThat(entry.kind()).as("no row means nothing knows of a map image").isEqualTo("FEATURES");
		assertThat(entry.hasOgcFeatures()).as("importable all the same -- the directory says how").isTrue();
		assertThat(entry.datasetUri())
				.isEqualTo("https://registry.gdi-de.org/id/de.hh/948321ba-e9b2-4290-88c3-8dda2912defa");
		assertThat(entry.wmsUrl()).as("no row means no WMS-Adresse either").isNull();
	}

	@Test
	@DisplayName("nur aus dem Internet erreichbare Zeilen werden Katalogeinträge")
	void onlyPubliclyReachableRowsSurvive() {
		assertThat(load()).extracting(GeoportalCatalogEntry::title).doesNotContain("Baugenehmigungen");
	}

	/**
	 * A row the service directory knows nothing about is listed as it always was: no OGC API
	 * Features binding, an id built from its metadata record. 108 of the 511 public rows are
	 * in this position, and the catalog would be smaller than it is today without them.
	 */
	@Test
	@DisplayName("eine Zeile ohne Dienst im Verzeichnis bleibt ein Eintrag, Art WMS, Kennung aus dem Metadatensatz")
	void aRowTheServiceDirectoryDoesNotKnowStaysAnEntry() {
		GeoportalCatalogEntry entry = byId(load(), "md:f691cfb0-d38f-4308-b12f-1671166ff181");

		assertThat(entry.title()).isEqualTo("ALKIS - Flurstücke und Gebäude (gelb)");
		assertThat(entry.kind()).isEqualTo("WMS");
		assertThat(entry.hasOgcFeatures()).isFalse();
		assertThat(entry.collectionCount()).isEqualTo(1);
		assertThat(entry.wmsUrl()).isEqualTo("https://geodienste.hamburg.de/HH_WMS_Fachdaten_ALKIS?SERVICE=WMS");
	}

	/**
	 * Nearly half the catalog looks like this: an {@code Organisation} Hamburg itself does
	 * not abbreviate. There is no reliable way to shorten such a name, so the whole string
	 * has to survive as the {@code agency} the list row and the agency filter show -- and it
	 * must arrive complete, not empty and not cut at some assumed separator.
	 */
	@Test
	@DisplayName("eine Organisation ohne Klammer-Abkürzung behält ihren vollen Namen als Behörde")
	void agencyKeepsTheFullNameWhenTheOrganisationCarriesNoAbbreviation() {
		GeoportalCatalogEntry entry = byId(load(), "md:f691cfb0-d38f-4308-b12f-1671166ff181");

		assertThat(entry.agency()).isEqualTo("Landesbetrieb Geoinformation und Vermessung");
		assertThat(entry.attribution()).isEqualTo("Landesbetrieb Geoinformation und Vermessung");
	}

	// --- two shapes, decided by size -----------------------------------------------------

	/**
	 * CONTRACT.md 11.9: {@code xplan}'s collections are the object classes of one data model,
	 * and 247 of them in the list would bury everything else. The service is one row, and the
	 * row cannot be imported -- it names no collection to import from.
	 */
	@Test
	@DisplayName("ein Dienst ab 20 Sammlungen erscheint als eine Zeile, seine Sammlungen erst im Detail")
	void aServiceAtTheThresholdIsListedAsASingleRow() {
		List<GeoportalCatalogEntry> entries = load();

		assertThat(entries).extracting(GeoportalCatalogEntry::id).filteredOn(id -> id.startsWith("xplan"))
				.containsExactly("xplan");

		GeoportalCatalogEntry service = byId(entries, "xplan");
		assertThat(service.title()).isEqualTo("XPlanungsdaten Hamburg");
		assertThat(service.isService()).isTrue();
		assertThat(service.collectionCount()).isEqualTo(XPLAN_COLLECTIONS.size());
		assertThat(service.hasOgcFeatures()).as("no collection is chosen yet").isFalse();
		assertThat(service.collection()).isNull();
		assertThat(service.agency()).isEqualTo("BSW");
		assertThat(service.topic()).isEqualTo("Regionen und Städte");
	}

	@Test
	@DisplayName("die Sammlungen eines solchen Dienstes tragen eigene Kennung, eigenen Namen und die Angaben des Dienstes")
	void theCollectionsOfSuchAServiceAreCompleteEntries() {
		GeoportalCatalogEntry service = byId(load(), "xplan");

		assertThat(service.collections()).extracting(GeoportalCatalogEntry::id)
				.startsWith("xplan/bp_abgrabungsflaeche", "xplan/bp_abstandsflaeche");
		assertThat(service.collections()).extracting(GeoportalCatalogEntry::title)
				.startsWith("BP_AbgrabungsFlaeche", "BP_AbstandsFlaeche");
		assertThat(service.collections()).allSatisfy(collection -> {
			assertThat(collection.hasOgcFeatures()).as("each one is importable on its own").isTrue();
			assertThat(collection.apiUrl()).isEqualTo(API + "xplan");
			assertThat(collection.agency()).isEqualTo("BSW");
			assertThat(collection.collectionCount()).isEqualTo(1);
		});
	}

	/**
	 * A service just below the line stays flat. The threshold decides between two shapes and
	 * nothing else -- five collections are five rows, exactly as before this change was made.
	 */
	@Test
	@DisplayName("ein Dienst unterhalb der Schwelle bleibt flach")
	void aServiceBelowTheThresholdStaysFlat() {
		assertThat(load()).filteredOn(entry -> entry.id().startsWith("biotopverbund_trockenlebensraeume"))
				.hasSize(5)
				.allSatisfy(entry -> assertThat(entry.isService()).isFalse());
	}

	// --- titles ---------------------------------------------------------------------------

	/**
	 * CONTRACT.md 11.9, the same rule 11.4 applies to field names: the first occurrence keeps
	 * the name it was given, the second and every later one is qualified. Measured live, 24
	 * titles repeat across 66 entries; "Verbindungsräume" is one of them, in four services.
	 */
	@Test
	@DisplayName("ein mehrdeutiger Sammlungsname wird ab dem zweiten Auftreten mit dem Dienstnamen ergänzt")
	void theSecondOccurrenceOfAnAmbiguousTitleIsQualified() {
		List<GeoportalCatalogEntry> entries = load();

		assertThat(byId(entries, "biotopverbund_feuchtlebensraeume/verbindungsraeume").title())
				.as("the first occurrence is never dressed up")
				.isEqualTo("Verbindungsräume");
		assertThat(byId(entries, "biotopverbund_trockenlebensraeume/verbindungsraeume").title())
				.isEqualTo(TROCKEN_SERVICE + ": Verbindungsräume");
	}

	@Test
	@DisplayName("ein eindeutiger Name bleibt unverändert")
	void aUniqueTitleIsNeverQualified() {
		assertThat(byId(load(), "strassenbaumkataster/strassenbaumkataster_hh").title())
				.isEqualTo("Straßenbaumkataster Hamburg");
	}

	/**
	 * The service's own name is the collection's qualifier, not its title: a collection is
	 * listed under the name Hamburg gives that collection. Before this change the same five
	 * rows would have been one row named after the dataset.
	 */
	@Test
	@DisplayName("der Titel ist der Sammlungsname, nicht der Dienstname")
	void aCollectionIsListedUnderItsOwnName() {
		assertThat(load()).extracting(GeoportalCatalogEntry::title)
				.contains("Hauptverbundachsen", "Kernflächen", "Länderübergreifende Anknüpfungspunkte")
				.doesNotContain(FEUCHT_SERVICE);
	}

	// --- the whole listing -----------------------------------------------------------------

	/**
	 * Five services and one unbound row, which is 5 + 5 + 1 + 1 collections listed flat, one
	 * row for {@code xplan}, and the ALKIS row -- the same arithmetic the real files answer
	 * with 1094 flat entries, eight service rows and 108 unbound rows.
	 */
	@Test
	@DisplayName("der Katalog zählt Sammlungen, nicht Dienste")
	void theListingCountsCollectionsRatherThanServices() {
		List<GeoportalCatalogEntry> entries = load();

		assertThat(entries).hasSize(14);
		assertThat(entries).filteredOn(GeoportalCatalogEntry::isService).hasSize(1);
		assertThat(entries).extracting(GeoportalCatalogEntry::collectionCount)
				.filteredOn(count -> count > 1)
				.containsExactly(XPLAN_COLLECTIONS.size());
		assertThat(entries).extracting(GeoportalCatalogEntry::id).doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("gfiAttributes und Sammlungsliste sind nie null")
	void mapsAndListsAreNeverNull() {
		assertThat(load()).allSatisfy(entry -> {
			assertThat(entry.gfiAttributes()).isNotNull();
			assertThat(entry.collections()).isNotNull();
		});
	}

	/** Guards the one place the old loader put its arbitrary choice: it kept the first collection and dropped the rest. */
	@Test
	@DisplayName("keine Sammlung eines Dienstes verdrängt eine andere")
	void noCollectionOfAServiceEverReplacesAnother() {
		List<GeoportalCatalogEntry> entries = load();

		Map<String, Long> perService = entries.stream()
				.filter(GeoportalCatalogEntry::hasOgcFeatures)
				.collect(Collectors.groupingBy(entry -> entry.id().split("/")[0], Collectors.counting()));

		assertThat(perService).containsEntry("biotopverbund_feuchtlebensraeume", 5L)
				.containsEntry("biotopverbund_trockenlebensraeume", 5L);
	}
}
