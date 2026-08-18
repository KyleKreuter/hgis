package de.kreuter.hgis.catalog;

import de.kreuter.hgis.jobs.AsyncConfig;
import de.kreuter.hgis.jobs.JobService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a duplicate in short transactions: create its catalog shell, copy one
 * layer at a time, then finish. A failed run deletes the complete target project.
 */
@Service
public class ProjectDuplicateService {

	private static final Logger log = LoggerFactory.getLogger(ProjectDuplicateService.class);

	private final ProjectDuplicateTransactions transactions;
	private final JobService jobService;

	ProjectDuplicateService(ProjectDuplicateTransactions transactions, JobService jobService) {
		this.transactions = transactions;
		this.jobService = jobService;
	}

	@Async(AsyncConfig.IMPORT_EXECUTOR)
	public void runDuplicateAsync(UUID jobId, UUID sourceProjectId, String name) {
		runDuplicate(jobId, sourceProjectId, name);
	}

	/** Synchronous counterpart used by integration tests. */
	public void runDuplicate(UUID jobId, UUID sourceProjectId, String name) {
		ProjectDuplicateTransactions.Start start;
		try {
			start = transactions.start(jobId, sourceProjectId, name);
		} catch (Exception e) {
			log.error("Could not start duplicate {}", jobId, e);
			jobService.markFailed(jobId, describe(e));
			return;
		}

		try {
			for (UUID sourceLayerId : start.sourceLayerIds()) {
				transactions.copyLayer(jobId, sourceLayerId, start.targetProjectId(), start.totalFeatures());
			}
			transactions.complete(jobId, start.targetProjectId());
		} catch (Exception e) {
			log.error("Duplicate {} failed, compensating target {}", jobId, start.targetProjectId(), e);
			transactions.compensateAndFail(jobId, start.targetProjectId(), describe(e));
		}
	}

	private static String describe(Exception e) {
		return e.getMessage() == null || e.getMessage().isBlank()
				? "Das Programm konnte das Projekt nicht duplizieren" : e.getMessage();
	}
}
