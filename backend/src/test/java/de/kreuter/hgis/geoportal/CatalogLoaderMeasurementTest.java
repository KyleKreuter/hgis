package de.kreuter.hgis.geoportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link CatalogLoader} against the two real files, whole, rather than against a fixture cut
 * from them: 6539 directory entries and 596 dataset rows in, a catalog of a known size out.
 * Every number below was measured before the loader was written and is what CONTRACT.md 11.9
 * counts with -- a change that moves any of them changes what the dialog shows.
 *
 * <p>The files are local copies under {@code scratchpad/}, 7.9&nbsp;MB together and outside
 * the repository, so this test skips itself where they are absent -- on CI, and on any
 * checkout that has not fetched them. It is a measurement kept runnable, not a gate: what
 * the loader does with the two shapes is {@link CatalogLoaderTest}'s job, and that one runs
 * everywhere.
 *
 * <p>To run it, fetch both files into {@code scratchpad/} next to the {@code backend}
 * directory:
 *
 * <pre>
 * curl -o scratchpad/services.json https://geodienste.hamburg.de/services-internet.json
 * curl -o scratchpad/datasets.csv  https://geoportal-hamburg.de/urbandataplatform/datasets.csv
 * </pre>
 */
class CatalogLoaderMeasurementTest {

	/** Relative to the module directory, which is where surefire runs. */
	private static final Path SERVICE_DIRECTORY = Path.of("..", "scratchpad", "services.json");
	private static final Path DATASET_LIST = Path.of("..", "scratchpad", "datasets.csv");

	/** Collections listed on their own. */
	private static final int FLAT_ENTRIES = 1094;

	/** Rows of the dataset list the service directory does not know; listed, not importable. */
	private static final int UNBOUND_DATASETS = 108;

	/** The eight services that carry 20 collections or more, and how many each carries. */
	private static final Map<String, Integer> SERVICES_LISTED_AS_ONE_ROW = Map.of(
			"xplan", 247,
			"lig_grundbesitz", 58,
			"hwrm_1_zyklus", 37,
			"hwrm_2_zyklus", 35,
			"hwrm_3_zyklus", 29,
			"strassen_und_wegenetz", 28,
			"zeb_zustandsklassen", 21,
			"industriekultur", 20);

	private static final int COLLECTIONS_BEHIND_THOSE_ROWS = 475;

	private static List<GeoportalCatalogEntry> loadRealFiles() throws IOException {
		assumeTrue(Files.exists(SERVICE_DIRECTORY) && Files.exists(DATASET_LIST),
				"Die Kopien der beiden Geoportal-Dateien fehlen unter scratchpad/ -- siehe Klassenkommentar");

		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://geodienste.hamburg.de/services-internet.json"))
				.andRespond(withSuccess(Files.readAllBytes(SERVICE_DIRECTORY), MediaType.APPLICATION_JSON));
		server.expect(requestTo("https://geoportal-hamburg.de/urbandataplatform/datasets.csv"))
				.andRespond(withSuccess(Files.readAllBytes(DATASET_LIST), MediaType.valueOf("text/csv")));

		return new CatalogLoader(builder.build()).load();
	}

	private static long titledExactly(List<GeoportalCatalogEntry> entries, String title) {
		return entries.stream().filter(entry -> entry.title().equals(title)).count();
	}

	private static long titledWithAServiceInFrontOf(List<GeoportalCatalogEntry> entries, String title) {
		return entries.stream().filter(entry -> entry.title().endsWith(": " + title)).count();
	}

	/**
	 * The count CONTRACT.md 11.9 states: 1094 collections listed flat and eight services
	 * listed as one row each. Before this change the same 405 services produced 405 entries,
	 * and the other 1164 collections could not be reached at all.
	 */
	@Test
	@DisplayName("aus dem echten Dienstverzeichnis entstehen 1094 flache Einträge und 8 Dienstzeilen")
	void theRealServiceDirectoryYieldsTheMeasuredCounts() throws IOException {
		List<GeoportalCatalogEntry> entries = loadRealFiles();

		assertThat(entries).filteredOn(entry -> entry.hasOgcFeatures() && !entry.isService())
				.as("Sammlungen, die für sich gelistet werden").hasSize(FLAT_ENTRIES);
		assertThat(entries).filteredOn(GeoportalCatalogEntry::isService)
				.as("Dienste, die als eine Zeile erscheinen").hasSize(SERVICES_LISTED_AS_ONE_ROW.size());
		assertThat(entries).filteredOn(GeoportalCatalogEntry::isService)
				.flatExtracting(GeoportalCatalogEntry::collections)
				.as("deren Sammlungen, im Detail wählbar").hasSize(COLLECTIONS_BEHIND_THOSE_ROWS);
	}

	@Test
	@DisplayName("die acht zweistufigen Dienste sind genau die gemessenen")
	void theEightTwoStageServicesAreTheMeasuredOnes() throws IOException {
		Map<String, Integer> listedAsOneRow = loadRealFiles().stream()
				.filter(GeoportalCatalogEntry::isService)
				.collect(Collectors.toMap(GeoportalCatalogEntry::id, GeoportalCatalogEntry::collectionCount));

		assertThat(listedAsOneRow).isEqualTo(SERVICES_LISTED_AS_ONE_ROW);
	}

	/**
	 * The 108 rows of the dataset list the service directory does not know keep their place
	 * in the catalog -- they are datasets Hamburg publishes, listed as they were before, just
	 * not importable in this stage. Dropping them would have made the catalog smaller than it
	 * already was, in a change whose whole purpose is to make it larger.
	 */
	@Test
	@DisplayName("die Datensätze ohne Dienst im Verzeichnis bleiben im Katalog")
	void datasetsWithoutAServiceStayInTheCatalog() throws IOException {
		List<GeoportalCatalogEntry> entries = loadRealFiles();

		assertThat(entries).filteredOn(entry -> !entry.hasOgcFeatures() && !entry.isService())
				.hasSize(UNBOUND_DATASETS);
		assertThat(entries).as("und zusammen ist das der ganze Katalog")
				.hasSize(FLAT_ENTRIES + SERVICES_LISTED_AS_ONE_ROW.size() + UNBOUND_DATASETS);
		assertThat(entries).extracting(GeoportalCatalogEntry::id).doesNotHaveDuplicates();
	}

	/**
	 * CONTRACT.md 11.9's own case: "Verbindungsräume" is the name four services give a
	 * collection, and "2013" the name three give one. The first keeps it, the others carry
	 * their service in front of it -- and after that no two rows of the catalog read alike,
	 * which is the whole point of the rule.
	 */
	@Test
	@DisplayName("mehrdeutige Titel werden ab dem zweiten Auftreten ergänzt, sodass kein Titel doppelt vorkommt")
	void ambiguousTitlesAreQualifiedFromTheSecondOccurrenceOn() throws IOException {
		List<GeoportalCatalogEntry> entries = loadRealFiles();

		assertThat(titledExactly(entries, "Verbindungsräume")).isEqualTo(1);
		assertThat(titledWithAServiceInFrontOf(entries, "Verbindungsräume")).isEqualTo(3);
		assertThat(titledExactly(entries, "2013")).isEqualTo(1);
		assertThat(titledWithAServiceInFrontOf(entries, "2013")).isEqualTo(2);

		assertThat(entries).extracting(GeoportalCatalogEntry::title).doesNotHaveDuplicates();
	}

	/**
	 * The listing is not the lookup (CONTRACT.md 11.9): the 475 collections of the eight
	 * services stay out of the list and must still be found by their own id, which is what a
	 * detail request and the import after it name.
	 */
	@Test
	@DisplayName("jede Sammlung eines zweistufigen Dienstes ist über ihre eigene Kennung erreichbar")
	void everyCollectionOfATwoStageServiceIsResolvableById() throws IOException {
		List<GeoportalCatalogEntry> entries = loadRealFiles();
		CatalogLoader loader = mock(CatalogLoader.class);
		when(loader.load()).thenReturn(entries);
		GeoportalCatalogService catalog = new GeoportalCatalogService(loader);

		List<GeoportalCatalogEntry> hidden = entries.stream()
				.filter(GeoportalCatalogEntry::isService)
				.flatMap(service -> service.collections().stream())
				.toList();

		assertThat(hidden).allSatisfy(collection ->
				assertThat(catalog.find(collection.id())).contains(collection));
		assertThat(catalog.current().byId())
				.hasSize(FLAT_ENTRIES + SERVICES_LISTED_AS_ONE_ROW.size() + UNBOUND_DATASETS
						+ COLLECTIONS_BEHIND_THOSE_ROWS);
		assertThat(catalog.current().entries()).as("die Liste selbst zeigt sie nicht")
				.hasSize(FLAT_ENTRIES + SERVICES_LISTED_AS_ONE_ROW.size() + UNBOUND_DATASETS);
	}

	/**
	 * Every collection of a service inherits the agency, topic and licence of the dataset
	 * list's row for that service -- so a collection that was unreachable until now arrives
	 * with the same provenance the service's first collection always had.
	 *
	 * <p>Four services have no agency: two the dataset list does not mention, and two whose
	 * row leaves {@code Organisation} blank. One of those is
	 * {@code grundwassermessstellen}, the dataset CONTRACT.md 11.7 names -- importable, 191
	 * 140 features, and without a word on who publishes it. Their seven collections are the
	 * only ones in the whole catalog with no agency, and each still carries its licence.
	 */
	@Test
	@DisplayName("Sammlungen erben Behörde und Thema ihres Dienstes")
	void collectionsInheritTheAgencyOfTheirService() throws IOException {
		List<GeoportalCatalogEntry> entries = loadRealFiles();

		assertThat(entries).filteredOn(entry -> entry.id().startsWith("biotopverbund_feuchtlebensraeume/"))
				.hasSize(5)
				.allSatisfy(entry -> {
					assertThat(entry.agency()).isEqualTo("BUKEA");
					assertThat(entry.topic()).isEqualTo("Umwelt");
				});

		assertThat(entries).filteredOn(entry -> entry.hasOgcFeatures() && entry.agency() == null)
				.hasSize(7)
				.extracting(entry -> entry.id().substring(0, entry.id().indexOf('/')))
				.containsOnly("clubkataster", "gages_vereinfacht", "grundwassermessstellen", "lod2_hamburg");
	}
}
