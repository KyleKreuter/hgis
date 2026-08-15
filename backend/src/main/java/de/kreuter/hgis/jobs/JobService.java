package de.kreuter.hgis.jobs;

import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.jobs.dto.JobDtos;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code job} row and its status transitions PENDING -> RUNNING ->
 * SUCCEEDED/FAILED. Every public method here is its own short transaction; callers that
 * need a job update to commit together with other work (the layer catalog row, a batch
 * of features) call these methods from within their own {@code @Transactional} method so
 * the calls join that transaction instead of opening a new one.
 */
@Service
public class JobService {

	private final JobRepository repository;
	private final JobParameters parameters;

	JobService(JobRepository repository, JobParameters parameters) {
		this.repository = repository;
		this.parameters = parameters;
	}

	/** Creates a job in PENDING. Flushed immediately so createdAt is populated for the
	 *  202 response the controller returns right after this call. */
	@Transactional
	public Job create(UUID projectId, Job.Type type, String filename) {
		return repository.saveAndFlush(new Job(projectId, type, filename));
	}

	@Transactional
	public void markRunning(UUID jobId, UUID outputLayerId, Long totalCount) {
		Job job = require(jobId);
		job.markRunning();
		job.setOutputLayerId(outputLayerId);
		job.updateProgress(0, totalCount, 0);
	}

	/** Starts a duplicate job and records its target in the existing JSONB payload. */
	@Transactional
	public void markDuplicateRunning(UUID jobId, UUID outputProjectId, Long totalCount) {
		Job job = require(jobId);
		job.markRunning();
		job.setParameters(parameters.duplicate(outputProjectId));
		job.updateProgress(0, totalCount, 0);
	}

	/**
	 * Starts a job that produces no layer -- {@code places.PlaceRefreshService} is the
	 * first caller. Everything {@link #markRunning(UUID, UUID, Long)} does except setting
	 * {@code outputLayerId}, the same way {@link #markDuplicateRunning} leaves it unset for
	 * a job whose result is a project rather than a layer.
	 */
	@Transactional
	public void markRunning(UUID jobId, Long totalCount) {
		Job job = require(jobId);
		job.markRunning();
		job.updateProgress(0, totalCount, 0);
	}

	@Transactional
	public void updateProgress(UUID jobId, long processedCount, Long totalCount, long skippedCount) {
		require(jobId).updateProgress(processedCount, totalCount, skippedCount);
	}

	@Transactional
	public void markSucceeded(UUID jobId, String message) {
		require(jobId).markSucceeded(message);
	}

	@Transactional
	public void markFailed(UUID jobId, String message) {
		require(jobId).markFailed(message);
	}

	@Transactional(readOnly = true)
	public JobDtos.Response get(UUID jobId) {
		return toResponse(require(jobId));
	}

	private Job require(UUID jobId) {
		return repository.findById(jobId)
				.orElseThrow(() -> new NotFoundException("Job " + jobId + " existiert nicht"));
	}

	private JobDtos.Response toResponse(Job job) {
		return new JobDtos.Response(
				job.getId(), job.getType().name(), job.getStatus().name(), job.getFilename(),
				job.getProcessedCount(), job.getTotalCount(), job.getSkippedCount(), job.getOutputLayerId(),
				outputProjectId(job), job.getMessage(), job.getStartedAt(), job.getFinishedAt(), job.getCreatedAt());
	}

	private UUID outputProjectId(Job job) {
		return parameters.outputProjectId(job.getParameters());
	}
}
