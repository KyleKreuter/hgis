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
import org.springframework.core.task.TaskRejectedException;
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
		CountDownLatch release = new CountDownLatch(1);

		// An import from another test may still be finishing in this pool. Saturating a pool
		// that is not empty to begin with fills it one task short and proves nothing.
		awaitIdlePool();

		MockHttpServletResponse response;
		try {
			saturate(release);

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
		awaitIdlePool();

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
	 * Blocks every thread and every waiting place, and stops at the first task the pool
	 * refuses.
	 *
	 * <p>Submitting a fixed number would be the obvious way, and this pool does not allow
	 * it. Its core size is smaller than its maximum, and the JDK grows a pool past its core
	 * only for a task it cannot queue -- so whether a submission takes a thread or a place
	 * in the queue depends on how many threads an earlier test happened to leave alive, and
	 * a count that is right for an empty pool is one or two short for a warm one.
	 *
	 * <p>Submitting until the refusal needs none of that arithmetic. Nothing submitted here
	 * can finish before {@code release}, so every accepted task is still holding either a
	 * thread or a place in the queue, and the refusal is itself the proof that neither is
	 * left. The bound only keeps a pool that refuses nothing from looping forever.
	 */
	private void saturate(CountDownLatch release) {
		int capacity = importExecutor.getMaxPoolSize() + importExecutor.getQueueCapacity();

		for (int submitted = 0; submitted <= capacity; submitted++) {
			try {
				importExecutor.execute(() -> awaitQuietly(release));
			}
			catch (TaskRejectedException full) {
				return;
			}
		}

		throw new AssertionError(
				"the import pool accepted more than " + capacity + " tasks that never finish");
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
