package de.kreuter.hgis.geoportal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * What the held catalog does when more than one request arrives at once. The single-threaded
 * behaviour -- merge, fallback, lookup -- belongs to {@link CatalogLoaderTest} and {@link
 * GeoportalDatasetServiceTest}; this suite only exercises the concurrency, with a loader
 * that reports how often and how overlappingly it was called.
 */
class GeoportalCatalogServiceTest {

	/** Long enough that two threads racing for the first load overlap reliably. */
	private static final Duration LOAD_DURATION = Duration.ofMillis(200);

	/**
	 * Stands in for the two upstream files: slow, like the 7.6&nbsp;MB fetch it replaces,
	 * and counting both how often it ran and how many runs ever overlapped.
	 */
	private static final class RecordingLoader extends CatalogLoader {

		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicInteger running = new AtomicInteger();
		private final AtomicInteger maxRunning = new AtomicInteger();

		RecordingLoader() {
			// Never used: load() below never calls up, so no request is ever made.
			super(RestClient.builder().build());
		}

		@Override
		List<GeoportalCatalogEntry> load() {
			int number = calls.incrementAndGet();
			maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
			try {
				Thread.sleep(LOAD_DURATION);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			running.decrementAndGet();
			return List.of(new GeoportalCatalogEntry("lauf-" + number, "Datensatz " + number, "FEATURES",
					"BUKEA", null, "Umwelt", null, null, null, null, Map.of()));
		}
	}

	/** Runs {@code task} on {@code threads} threads that all start at the same moment. */
	private static void inParallel(int threads, Runnable task) throws InterruptedException {
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		for (int i = 0; i < threads; i++) {
			Thread thread = new Thread(() -> {
				try {
					start.await();
					task.run();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				finally {
					done.countDown();
				}
			});
			thread.setDaemon(true);
			thread.start();
		}
		start.countDown();
		assertThat(done.await(30, TimeUnit.SECONDS)).as("alle Ladeversuche sind fertig geworden").isTrue();
	}

	/**
	 * CONTRACT.md 11.2 pays for the catalog exactly once per session, not once per request
	 * that happened to arrive before the first one finished. Checking whether a snapshot is
	 * held and putting one there are two steps; two browser windows opened right after a
	 * restart run them interleaved and each start their own 7.6&nbsp;MB fetch.
	 */
	@Test
	@DisplayName("das erste Laden des Katalogs läuft auch bei gleichzeitigen Anfragen nur einmal")
	void theFirstLoadRunsOnceEvenWhenSeveralRequestsArriveTogether() throws InterruptedException {
		RecordingLoader loader = new RecordingLoader();
		GeoportalCatalogService service = new GeoportalCatalogService(loader);

		inParallel(4, service::current);

		assertThat(loader.calls).hasValue(1);
		assertThat(service.current().entries()).extracting(GeoportalCatalogEntry::id).containsExactly("lauf-1");
	}

	/**
	 * Two refreshes that overlap finish in whatever order the network decides, so the older
	 * result can be the one left held -- a button whose whole purpose is a newer catalog
	 * would have produced an older one. Serialising the loads removes the race at its
	 * source: the second refresh cannot start before the first is held.
	 */
	@Test
	@DisplayName("gleichzeitige Aktualisierungen überholen einander nicht")
	void concurrentRefreshesNeverOverlap() throws InterruptedException {
		RecordingLoader loader = new RecordingLoader();
		GeoportalCatalogService service = new GeoportalCatalogService(loader);

		inParallel(3, service::refresh);

		assertThat(loader.calls).as("jede Aktualisierung holt neu, das ist ihr Zweck").hasValue(3);
		assertThat(loader.maxRunning).as("aber nie zwei zugleich").hasValue(1);
		assertThat(service.current().entries()).extracting(GeoportalCatalogEntry::id)
				.as("der zuletzt beendete Lauf ist der gehaltene")
				.containsExactly("lauf-3");
	}
}
