package de.kreuter.hgis.jobs;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated executor for imports (and later geoprocessing), kept separate from Spring's
 * common pool so a burst of uploads cannot starve unrelated {@code @Async} work
 * elsewhere in the application -- and vice versa, a slow import never queues behind it.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

	public static final String IMPORT_EXECUTOR = "importExecutor";

	@Bean(IMPORT_EXECUTOR)
	public Executor importExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		// Imports are I/O bound (file parsing, many short DB transactions), not CPU bound,
		// so a handful of threads comfortably covers a laptop-scale deployment.
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("import-");
		executor.initialize();
		return executor;
	}
}
