package de.kreuter.hgis.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.jobs.AsyncConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What a client is told, and what is left behind, when the import pool is full.
 *
 * <p>The pool is bounded on purpose -- four threads and fifty waiting places -- so being
 * turned away is a normal outcome. It happens at the worst possible moment, though: the
 * upload is stored, the job is created, the reader is open, and the only thing missing is a
 * thread to do the work on. Left alone, the rejection took all three with it -- a 500 that
 * blamed the server for a queue, a job stuck at PENDING that no janitor ever revisits, and
 * an open reader still holding the directory a Shapefile was extracted into.
 *
 * <p>The rejection is synchronous, unlike the export's: {@code runImportAsync} is submitted
 * from the request thread and throws there, so nothing of the response has been written yet
 * and {@link ImportOverloadAdvice} can still choose the status.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportOverloadTest {

	/** Everything the pool accepts: what runs at full stretch, plus what waits. */
	private static final int CAPACITY = 4 + 50;

	private static final int POOL_SIZE = 4;

	private static final int DRAIN_TIMEOUT_SECONDS = 20;

	/** Hamburg in EPSG:25832, the same shape {@code InspectControllerTest} imports. */
	private static final String UTM_CSV = """
			rechtswert;hochwert;strasse
			566000,00;5934000,00;Müllerstraße
			566100,00;5934100,00;Bäckerweg
			""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	@Qualifier(AsyncConfig.IMPORT_EXECUTOR)
	private ThreadPoolTaskExecutor importExecutor;

	private Project project;

	@BeforeAll
	void createProject() {
		project = projectRepository.saveAndFlush(
				new Project("Import-Last " + UUID.randomUUID(), null, 25832, "osm"));
	}

	@AfterAll
	void dropProject() {
		projectRepository.deleteById(project.getId());
	}

	@Test
	@DisplayName("one import past the pool and the queue is a 503, and the job says so")
	void refusesAnImportWhenThePoolIsFull() throws Exception {
		CountDownLatch running = new CountDownLatch(POOL_SIZE);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(CAPACITY);

		// An import from another test may still be finishing in this pool. Saturating a pool
		// that is not empty to begin with fills it one task short and proves nothing.
		awaitIdlePool();

		MockHttpServletResponse response;
		try {
			saturate(running, release, finished);

			response = mockMvc.perform(multipart("/api/projects/{id}/imports", project.getId())
							.file(new MockMultipartFile("file", "adressen.csv", "text/csv",
									UTM_CSV.getBytes(StandardCharsets.UTF_8)))
							.param("srid", "25832")
							.param("name", "Abgewiesen"))
					.andReturn()
					.getResponse();
		}
		finally {
			release.countDown();
		}
		// Leaving blocked tasks behind would fail every test that comes after.
		assertThat(finished.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
				.as("the pool drains again once the tasks are let go")
				.isTrue();

		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentType()).startsWith("application/problem+json");
		assertThat(response.getHeader(HttpHeaders.RETRY_AFTER))
				.as("a refusal the client can act on says when to come back")
				.isNotNull();

		List<String> statuses = jdbc
				.sql("SELECT status FROM gis_meta.job WHERE project_id = :projectId")
				.param("projectId", project.getId())
				.query(String.class)
				.list();
		assertThat(statuses)
				.as("the job the endpoint created before the rejection must not stay PENDING")
				.containsExactly("FAILED");
	}

	/**
	 * Fills threads and queue in that order, and waits in between.
	 *
	 * <p>Submitting everything in one burst would be a race: a task sits in the queue until a
	 * worker picks it up, so fifty-four in quick succession can be fifty-five in the queue
	 * for a moment. Filling the threads first, and only then the queue, leaves nothing that
	 * can drain -- every thread is blocked on {@code release}.
	 */
	private void saturate(CountDownLatch running, CountDownLatch release, CountDownLatch finished)
			throws InterruptedException {
		for (int i = 0; i < POOL_SIZE; i++) {
			importExecutor.execute(() -> {
				running.countDown();
				awaitQuietly(release);
				finished.countDown();
			});
			// One at a time: below its maximum this pool only grows a thread when the queue
			// is full, so a burst would queue the tasks instead of occupying threads with
			// them.
			assertThat(running.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			fillQueueUpTo(i + 1, release, finished);
		}

		assertThat(importExecutor.getThreadPoolExecutor().getQueue().remainingCapacity())
				.as("the pool has to be genuinely full for this to test anything")
				.isZero();
	}

	/**
	 * Keeps the queue full while the pool is still growing.
	 *
	 * <p>The JDK's pool only starts a thread beyond its core size for a task it cannot
	 * queue, so the queue has to be full before each of those. It is refilled here after
	 * every new thread, which takes exactly one task out of it.
	 */
	private void fillQueueUpTo(int threadsRunning, CountDownLatch release, CountDownLatch finished) {
		ThreadPoolExecutor pool = importExecutor.getThreadPoolExecutor();
		while (pool.getQueue().remainingCapacity() > 0 && threadsRunning < POOL_SIZE + 1) {
			importExecutor.execute(() -> {
				awaitQuietly(release);
				finished.countDown();
			});
		}
	}

	private void awaitIdlePool() throws InterruptedException {
		ThreadPoolExecutor pool = importExecutor.getThreadPoolExecutor();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DRAIN_TIMEOUT_SECONDS);

		while (pool.getActiveCount() > 0 || !pool.getQueue().isEmpty()) {
			assertThat(System.nanoTime()).as("the import pool never became idle").isLessThan(deadline);
			TimeUnit.MILLISECONDS.sleep(20);
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
