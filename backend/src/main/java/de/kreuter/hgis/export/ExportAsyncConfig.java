package de.kreuter.hgis.export;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The pool that streaming responses run on.
 *
 * <p>Spring MVC hands a {@code StreamingResponseBody} to an executor and, without one
 * configured, uses a {@code SimpleAsyncTaskExecutor}: a new thread per request, unbounded.
 * Boot normally prevents that by supplying {@code applicationTaskExecutor} -- but that
 * bean carries {@code @ConditionalOnMissingBean(Executor.class)}, and
 * {@link de.kreuter.hgis.jobs.AsyncConfig} already declares one for imports, so it quietly
 * backs off. Nothing about the resulting setup looks wrong; it simply grows a thread for
 * every export in flight.
 *
 * <p>Sized against the connection pool rather than the CPU. An export holds one database
 * connection for as long as the client is reading, so the number that can run at once has
 * to stay well below {@code spring.datasource.hikari.maximum-pool-size} -- a pool drained
 * by downloads would take the rest of the application with it.
 *
 * <p>Applies to every asynchronous MVC handler, of which the export is currently the only
 * one.
 */
@Configuration(proxyBeanMethods = false)
class ExportAsyncConfig implements WebMvcConfigurer {

	static final String EXPORT_EXECUTOR = "exportExecutor";

	/**
	 * Exports that may run at the same time. Four of ten Hikari connections is the most
	 * that downloads are allowed to hold while the rest of the application keeps working.
	 */
	static final int POOL_SIZE = 4;

	/**
	 * A queue is a waiting room, not storage. Held short deliberately: a queued export has
	 * a client sitting on an open connection waiting for the first byte, so the useful
	 * length is "a brief peak", and anything longer only converts a rejection the client
	 * could retry into a timeout it cannot.
	 */
	static final int QUEUE_CAPACITY = 8;

	/** Idle threads, core ones included, are given back rather than parked forever. */
	private static final Duration KEEP_ALIVE = Duration.ofSeconds(60);

	/**
	 * Long enough for a genuinely large layer over a slow connection, short enough that a
	 * client which stopped reading cannot pin a thread and a connection indefinitely.
	 */
	private static final Duration TIMEOUT = Duration.ofMinutes(10);

	private final AsyncTaskExecutor exportExecutor;

	// The scheduler enabled for the upload janitor is an AsyncTaskExecutor as well, so
	// the executor has to be named rather than found by type.
	ExportAsyncConfig(@Qualifier(EXPORT_EXECUTOR) AsyncTaskExecutor exportExecutor) {
		this.exportExecutor = exportExecutor;
	}

	/** Static so the pool can be built before this configuration is instantiated from it. */
	@Bean(EXPORT_EXECUTOR)
	static ThreadPoolTaskExecutor exportExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		// Core and maximum are the same number on purpose. A ThreadPoolExecutor only
		// starts a thread beyond the core size once the queue is full, so a small core
		// with a queue behind it runs exports one after another no matter how many
		// threads the maximum permits -- the second download waits for the first to
		// finish rather than running beside it.
		executor.setCorePoolSize(POOL_SIZE);
		executor.setMaxPoolSize(POOL_SIZE);
		executor.setQueueCapacity(QUEUE_CAPACITY);
		// With a fixed pool the threads would otherwise stay for the life of the
		// application, four of them idle between two downloads a day.
		executor.setAllowCoreThreadTimeOut(true);
		executor.setKeepAliveSeconds((int) KEEP_ALIVE.toSeconds());
		executor.setThreadNamePrefix("export-");
		// Default rejection policy, deliberately: past twelve exports the honest answer
		// is that the server is busy, and ExportOverloadAdvice turns it into one.
		return executor;
	}

	@Override
	public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
		configurer.setTaskExecutor(exportExecutor);
		// Otherwise the container's own limit applies -- 30 seconds on Tomcat, which an
		// export of any size passes without trying, and the download simply stops.
		configurer.setDefaultTimeout(TIMEOUT.toMillis());
	}
}
