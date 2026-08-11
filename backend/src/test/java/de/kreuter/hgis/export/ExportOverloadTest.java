package de.kreuter.hgis.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.SqlIdentifier;
import jakarta.servlet.DispatcherType;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.context.request.async.WebAsyncUtils;

/**
 * What a client is told when the export pool is full.
 *
 * <p>The pool is bounded on purpose, so being turned away is a normal outcome and needs a
 * normal answer. Without one the rejection travels as a {@code TaskRejectedException} into
 * the catch-all and reads as 500 -- a server fault where the truth is a queue, and a
 * message that tells the user to report a bug rather than to try again in a minute.
 *
 * <p>It can be answered at all only because of where the rejection happens: Spring MVC
 * submits the task after the headers are set but before a single byte of the body exists,
 * and it is the first byte that commits the response. Later than that nothing could be
 * corrected -- but there is no later, because the task that would write those bytes is
 * precisely the one that was never accepted.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportOverloadTest {

	/** Everything the pool accepts: what runs, plus what waits. */
	private static final int CAPACITY =
			ExportAsyncConfig.POOL_SIZE + ExportAsyncConfig.QUEUE_CAPACITY;

	private static final int DRAIN_TIMEOUT_SECONDS = 10;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	@Qualifier(ExportAsyncConfig.EXPORT_EXECUTOR)
	private ThreadPoolTaskExecutor exportExecutor;

	private Project project;
	private Layer layer;
	private String tableName;

	@BeforeAll
	void createLayer() {
		project = projectRepository.saveAndFlush(
				new Project("Last-Test " + UUID.randomUUID(), null, 4326, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		jdbc.sql("CREATE TABLE " + SqlIdentifier.quoteLayerTable(tableName)
				+ " (fid bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
				+ " geom geometry(MultiPoint, 4326) NOT NULL)").update();
		jdbc.sql("INSERT INTO " + SqlIdentifier.quoteLayerTable(tableName)
				+ " (geom) VALUES (ST_Multi(ST_SetSRID(ST_MakePoint(9.9, 53.5), 4326)))").update();

		Layer newLayer = new Layer(layerId, project, "Last", tableName, "MULTIPOINT", 4326);
		newLayer.setFeatureCount(1);
		layer = layerRepository.saveAndFlush(newLayer);
	}

	@AfterAll
	void dropLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.deleteById(layer.getId());
		projectRepository.deleteById(project.getId());
	}

	@Test
	@DisplayName("one export past the pool and the queue is a 503, not a 500")
	void refusesAnExportWhenThePoolIsFull() throws Exception {
		CountDownLatch running = new CountDownLatch(ExportAsyncConfig.POOL_SIZE);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(CAPACITY);

		// The pool belongs to the whole context and an export from another test may still
		// be finishing in it. Saturating a pool that is not empty to begin with fills it
		// one task short and proves nothing.
		awaitIdlePool();

		MvcResult rejected;
		try {
			// First occupy every thread, and wait until they are all genuinely inside a
			// task. Filling threads and queue in one burst would be a race: a submitted
			// task sits in the queue until a worker picks it up, so twelve in quick
			// succession can be thirteen in the queue for a moment.
			for (int i = 0; i < ExportAsyncConfig.POOL_SIZE; i++) {
				exportExecutor.execute(() -> {
					running.countDown();
					awaitQuietly(release);
					finished.countDown();
				});
			}
			assertThat(running.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

			// Nothing can drain the queue now, so these stay in it and it ends up exactly
			// full.
			for (int i = 0; i < ExportAsyncConfig.QUEUE_CAPACITY; i++) {
				exportExecutor.execute(() -> {
					awaitQuietly(release);
					finished.countDown();
				});
			}
			assertThat(exportExecutor.getThreadPoolExecutor().getQueue().remainingCapacity())
					.as("the pool has to be genuinely full for this to test anything")
					.isZero();

			rejected = mockMvc.perform(
					get("/api/layers/{layerId}/export.geojson", layer.getId())).andReturn();
		}
		finally {
			release.countDown();
		}
		// Leaving twelve blocked tasks behind would fail every test that comes after.
		assertThat(finished.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
				.as("the pool drains again once the tasks are let go")
				.isTrue();

		// The rejection is recorded as the result of the asynchronous request and reaches
		// the exception handling on the dispatch that follows -- the same second pass the
		// container makes for a completed export, only with an exception in it. Until
		// then the response is the bare 200 the handler set up and never got to fill.
		assertThat(rejected.getRequest().isAsyncStarted()).isTrue();
		assertThat(concurrentResultOf(rejected)).isInstanceOf(RejectedExecutionException.class);

		MockHttpServletResponse response =
				mockMvc.perform(asyncDispatch(rejected.getRequest())).andReturn().getResponse();

		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentType()).startsWith("application/problem+json");
		assertThat(response.getHeader(HttpHeaders.RETRY_AFTER))
				.as("a refusal the client can act on says when to come back")
				.isNotNull();
	}

	/**
	 * The dispatch the container makes once an asynchronous request has its result.
	 *
	 * <p>{@code MockMvcRequestBuilders.asyncDispatch} would be the ready-made way, and it
	 * cannot be used here: it insists on an async result recorded in the {@code MvcResult},
	 * and the hook that records one is a callable interceptor that only runs around a task
	 * that was actually started. A rejected task never runs, so MockMvc never learns of
	 * the result -- while {@code WebAsyncManager} holds it and a real container dispatches
	 * on it regardless. This is that dispatch, and nothing more.
	 */
	private static RequestBuilder asyncDispatch(MockHttpServletRequest request) {
		request.setDispatcherType(DispatcherType.ASYNC);
		request.setAsyncStarted(false);
		return servletContext -> request;
	}

	private static Object concurrentResultOf(MvcResult result) {
		return WebAsyncUtils.getAsyncManager(result.getRequest()).getConcurrentResult();
	}

	private void awaitIdlePool() throws InterruptedException {
		ThreadPoolExecutor pool = exportExecutor.getThreadPoolExecutor();
		long deadline = System.nanoTime()
				+ TimeUnit.SECONDS.toNanos(DRAIN_TIMEOUT_SECONDS);

		while (pool.getActiveCount() > 0 || !pool.getQueue().isEmpty()) {
			assertThat(System.nanoTime())
					.as("the export pool never became idle")
					.isLessThan(deadline);
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
