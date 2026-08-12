package de.kreuter.hgis.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One long running operation: import, geoprocessing or project duplication. All three
 * share status handling, progress reporting, the polling endpoint and the janitor that
 * cleans up after a crash.
 *
 * The status transitions are intentionally encapsulated here rather than left to callers
 * mutating fields directly -- PENDING to RUNNING is checked, everything else is a
 * terminal transition that must always succeed so a compensating cleanup can never itself
 * get stuck.
 */
@Entity
@Table(name = "job")
public class Job {

	/** Mirrors the {@code job_type} CHECK constraint in V1__catalog.sql. */
	public enum Type {
		IMPORT, PROCESSING, DUPLICATE
	}

	/** Mirrors the {@code job_status} CHECK constraint in V1__catalog.sql. */
	public enum Status {
		PENDING, RUNNING, SUCCEEDED, FAILED
	}

	@Id
	// UUIDv7 like Project: the id is only ever assigned by Hibernate at insert time, so the
	// annotation-driven generator is fine here (unlike Layer, nothing needs the id up front).
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	/**
	 * Owning project. A plain column rather than a JPA relation: nothing here ever needs
	 * to navigate to the Project entity, only to read or write its id.
	 */
	@Column(name = "project_id")
	private UUID projectId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Type type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.PENDING;

	private String filename;

	private String algorithm;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String parameters;

	/**
	 * Layer produced by this job. Set as soon as the layer row exists -- at the start of
	 * an import, not only on success -- so a crash mid-import still tells the janitor
	 * which table to drop.
	 */
	@Column(name = "output_layer_id")
	private UUID outputLayerId;

	@Column(name = "processed_count", nullable = false)
	private long processedCount;

	@Column(name = "total_count")
	private Long totalCount;

	@Column(name = "skipped_count", nullable = false)
	private long skippedCount;

	/** Failure reason, or a warning left on an otherwise successful job. User facing. */
	private String message;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private Instant updatedAt;

	protected Job() {
		// for JPA
	}

	public Job(UUID projectId, Type type, String filename) {
		this.projectId = projectId;
		this.type = type;
		this.filename = filename;
	}

	/** PENDING -> RUNNING. Anything else indicates the job was started twice. */
	public void markRunning() {
		if (status != Status.PENDING) {
			throw new IllegalStateException(
					"Job " + id + " kann nicht zu RUNNING wechseln. Status ist " + status + ".");
		}
		this.status = Status.RUNNING;
		this.startedAt = Instant.now();
	}

	public void updateProgress(long processedCount, Long totalCount, long skippedCount) {
		this.processedCount = processedCount;
		this.totalCount = totalCount;
		this.skippedCount = skippedCount;
	}

	public void setOutputLayerId(UUID outputLayerId) {
		this.outputLayerId = outputLayerId;
	}

	public void setParameters(String parameters) {
		this.parameters = parameters;
	}

	/**
	 * Terminal transition to SUCCEEDED. Deliberately not guarded by a state check: this
	 * is also how a crash-recovered job would be resolved, and a compensating path must
	 * never itself fail because the job was already in some other state.
	 */
	public void markSucceeded(String message) {
		this.status = Status.SUCCEEDED;
		this.message = message;
		this.finishedAt = Instant.now();
	}

	/** Terminal transition to FAILED, callable from any state for the same reason. */
	public void markFailed(String message) {
		this.status = Status.FAILED;
		this.message = message;
		this.finishedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getProjectId() {
		return projectId;
	}

	public Type getType() {
		return type;
	}

	public Status getStatus() {
		return status;
	}

	public String getFilename() {
		return filename;
	}

	public String getAlgorithm() {
		return algorithm;
	}

	public String getParameters() {
		return parameters;
	}

	public UUID getOutputLayerId() {
		return outputLayerId;
	}

	public long getProcessedCount() {
		return processedCount;
	}

	public Long getTotalCount() {
		return totalCount;
	}

	public long getSkippedCount() {
		return skippedCount;
	}

	public String getMessage() {
		return message;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
