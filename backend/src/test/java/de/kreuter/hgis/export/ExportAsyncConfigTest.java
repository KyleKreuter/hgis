package de.kreuter.hgis.export;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * Streaming responses must not fall back to a thread per request -- and must not fall
 * back to one at a time either.
 *
 * <p>Worth a test of its own because both failures are invisible. Boot's
 * {@code applicationTaskExecutor} disappears the moment any other {@code Executor} bean
 * exists, exports keep working, and the only sign is a thread count that grows with the
 * downloads. The opposite mistake looks even healthier: a {@code ThreadPoolExecutor} grows
 * past its core size only once the queue is full, so a core of one behind a queue of
 * twenty-five runs every export in sequence no matter what the maximum says, and the
 * second user simply waits. Hence the core size is asserted, not only the maximum, and
 * four tasks are made to prove they overlap.
 */
// Same annotations as the other HTTP-level tests on purpose: Spring caches contexts by
// their configuration, so a set that differs by one annotation costs a second container.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ExportAsyncConfigTest {

	/** Long enough to survive a loaded CI machine, short enough to fail a serial pool. */
	private static final int OVERLAP_TIMEOUT_SECONDS = 10;

	@Autowired
	private RequestMappingHandlerAdapter handlerAdapter;

	@Autowired
	@Qualifier(ExportAsyncConfig.EXPORT_EXECUTOR)
	private ThreadPoolTaskExecutor exportExecutor;

	@Test
	@DisplayName("MVC async runs on the bounded export pool, not on SimpleAsyncTaskExecutor")
	void configuresTheExportExecutorForAsyncHandlers() {
		Object taskExecutor = ReflectionTestUtils.getField(handlerAdapter, "taskExecutor");

		assertThat(taskExecutor).isNotInstanceOf(SimpleAsyncTaskExecutor.class);
		assertThat(taskExecutor)
				.as("there is no accessor for what Spring MVC ended up using")
				.isSameAs(exportExecutor);
	}

	@Test
	@DisplayName("the pool is genuinely parallel: core equals maximum")
	void runsExportsSideBySide() {
		assertThat(exportExecutor.getCorePoolSize())
				.as("threads beyond the core start only once the queue is full, so a core "
						+ "below the maximum means the maximum is never reached")
				.isEqualTo(ExportAsyncConfig.POOL_SIZE);
		assertThat(exportExecutor.getMaxPoolSize()).isEqualTo(ExportAsyncConfig.POOL_SIZE);
		// Hikari allows ten connections; an export holds one for as long as the client
		// reads, so the pool must not be able to drain it.
		assertThat(exportExecutor.getMaxPoolSize()).isLessThan(10);
	}

	@Test
	@DisplayName("the queue is a short waiting room, and idle threads are given back")
	void boundsTheQueueAndRetiresIdleThreads() {
		assertThat(exportExecutor.getQueueCapacity())
				.isEqualTo(ExportAsyncConfig.QUEUE_CAPACITY)
				.isLessThanOrEqualTo(2 * ExportAsyncConfig.POOL_SIZE);
		assertThat(exportExecutor.getThreadPoolExecutor().allowsCoreThreadTimeOut())
				.as("otherwise four threads sit idle for the life of the application")
				.isTrue();
		assertThat(exportExecutor.getThreadNamePrefix()).isEqualTo("export-");
	}

	@Test
	@DisplayName("four exports really do run at the same time")
	void executesFourTasksConcurrently() throws Exception {
		int tasks = ExportAsyncConfig.POOL_SIZE;
		CountDownLatch started = new CountDownLatch(tasks);
		CountDownLatch release = new CountDownLatch(1);

		try {
			for (int i = 0; i < tasks; i++) {
				exportExecutor.execute(() -> {
					started.countDown();
					awaitQuietly(release);
				});
			}

			// A pool that runs them one after another never gets past the first, because
			// the first is still waiting for a latch only this thread can open.
			assertThat(started.await(OVERLAP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
					.as("all %s tasks started before any of them finished", tasks)
					.isTrue();
		}
		finally {
			release.countDown();
		}
	}

	@Test
	@DisplayName("the async timeout is raised above the container's 30 seconds")
	void allowsALongDownload() {
		Object timeout = ReflectionTestUtils.getField(handlerAdapter, "asyncRequestTimeout");

		assertThat(timeout).isNotNull();
		assertThat((Long) timeout).isGreaterThan(30_000L);
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(OVERLAP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
