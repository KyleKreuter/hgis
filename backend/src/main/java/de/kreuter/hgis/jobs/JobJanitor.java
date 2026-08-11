package de.kreuter.hgis.jobs;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.ProjectDeletionService;
import de.kreuter.hgis.common.TableCreator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs once after startup and cleans up after a crash.
 *
 * A job can only be RUNNING while some request thread or the import executor is actively
 * working on it. If the application just (re)started, nothing is running yet, so any job
 * still marked RUNNING was orphaned by a previous crash or forced shutdown -- its output
 * table is half written and must be treated exactly like an import failure.
 *
 * Also reports (but does not touch) tables in {@code gis_data} that have no catalog
 * entry, since those are a sign of a compensation that itself failed to run to
 * completion, or of an earlier, less careful version of this application.
 */
@Component
public class JobJanitor {

	private static final Logger log = LoggerFactory.getLogger(JobJanitor.class);

	private final JobRepository jobRepository;
	private final JobService jobService;
	private final LayerRepository layerRepository;
	private final TableCreator tableCreator;
	private final JdbcClient jdbc;
	private final ProjectDeletionService projectDeletionService;
	private final JobParameters parameters;

	JobJanitor(JobRepository jobRepository, JobService jobService, LayerRepository layerRepository,
			TableCreator tableCreator, JdbcClient jdbc, ProjectDeletionService projectDeletionService,
			JobParameters parameters) {
		this.jobRepository = jobRepository;
		this.jobService = jobService;
		this.layerRepository = layerRepository;
		this.tableCreator = tableCreator;
		this.jdbc = jdbc;
		this.projectDeletionService = projectDeletionService;
		this.parameters = parameters;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void cleanUpOrphanedJobs() {
		List<Job> orphaned = jobRepository.findByStatus(Job.Status.RUNNING);
		for (Job job : orphaned) {
			cleanUpOne(job.getId());
		}
		if (!orphaned.isEmpty()) {
			log.warn("Cleaned up {} job(s) orphaned by a previous crash (were RUNNING at startup)",
					orphaned.size());
		}

		reportUncatalogedTables();
	}

	@Transactional
	void cleanUpOne(UUID jobId) {
		Job job = jobRepository.findById(jobId).orElse(null);
		if (job == null || job.getStatus() != Job.Status.RUNNING) {
			return; // already handled, e.g. by a concurrent call; nothing left to do
		}

		String reason = job.getType() == Job.Type.DUPLICATE
				? "Anwendung wurde während der Projektduplizierung neu gestartet, Job wurde abgebrochen"
				: "Anwendung wurde während des Imports neu gestartet, Job wurde abgebrochen";
		if (job.getType() == Job.Type.DUPLICATE) {
			UUID outputProjectId = parameters.outputProjectId(job.getParameters());
			if (outputProjectId != null) {
				projectDeletionService.deleteProject(outputProjectId);
			}
			jobService.markFailed(jobId, reason);
			return;
		}
		if (job.getOutputLayerId() != null) {
			Layer layer = layerRepository.findById(job.getOutputLayerId()).orElse(null);
			if (layer != null) {
				tableCreator.dropLayer(layer.getId(), layer.getTableName());
				// The FK's ON DELETE SET NULL already cleared this column in the database,
				// but the Job instance cached in this transaction's persistence context
				// (loaded above) does not know that. Left alone, the full-column UPDATE
				// that markFailed triggers next would overwrite the NULL with this stale
				// in-memory value, leaving a dangling reference to a row that no longer
				// exists -- so the managed entity is corrected explicitly instead.
				job.setOutputLayerId(null);
				log.warn("Dropped half-written table {} for orphaned job {}", layer.getTableName(), jobId);
			}
		}
		jobService.markFailed(jobId, reason);
	}

	private void reportUncatalogedTables() {
		List<String> orphanTables = jdbc.sql("""
				SELECT t.table_name
				FROM information_schema.tables t
				WHERE t.table_schema = 'gis_data'
				  AND NOT EXISTS (
				      SELECT 1 FROM gis_meta.layer l WHERE l.table_name = t.table_name
				  )
				""").query(String.class).list();

		if (!orphanTables.isEmpty()) {
			log.warn("Found {} table(s) in gis_data without a catalog entry: {}",
					orphanTables.size(), orphanTables);
		}
	}
}
