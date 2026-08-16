package de.kreuter.hgis.changelog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One row of the write change log (CONTRACT.md "Schreibstufe" 1.2): what was written, to
 * which layer, by which client, and -- for a batch of deleted features -- the complete
 * rows that were removed.
 *
 * <p>Append-only. Nothing here is ever updated after insert; every column is {@code
 * updatable = false} for that reason, the same discipline {@code Job}'s immutable fields
 * follow. There is no delete path either -- a change_log row only ever disappears as a
 * side effect of its project being deleted (ON DELETE CASCADE, V13).
 */
@Entity
@Table(name = "change_log")
public class ChangeLogEntry {

	@Id
	// UUIDv7 like Job: the id is only ever assigned by Hibernate at insert time, nothing
	// needs it beforehand the way a layer's table name needs its id up front.
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@CreationTimestamp
	@Column(name = "occurred_at", updatable = false)
	private Instant occurredAt;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	/**
	 * Null exactly for a {@link ChangeLogAction#LAYER_PURGE} entry, written after the
	 * layer row is already gone -- see {@link ChangeLogService#record}. Every other
	 * action's layer still exists at the moment it is logged.
	 */
	@Column(name = "layer_id", updatable = false)
	private UUID layerId;

	/**
	 * Captured at write time rather than read through {@link #layerId}: once a layer is
	 * purged, {@link #layerId} turns null (ON DELETE SET NULL) and this is the only thing
	 * left that still says which layer an old entry belonged to.
	 */
	@Column(name = "layer_name", nullable = false, updatable = false)
	private String layerName;

	/** One of {@link ChangeLogAction}'s ten tokens. */
	@Column(nullable = false, updatable = false)
	private String action;

	/** The {@code X-Hgis-Client} of whoever wrote it, or null when they named none. */
	@Column(name = "client_name", updatable = false)
	private String clientName;

	@Column(name = "affected_count", nullable = false, updatable = false)
	private int affectedCount;

	/**
	 * Geometry (GeoJSON, EPSG:4326) and attributes (keyed by column_name) of every row a
	 * {@link ChangeLogAction#FEATURE_DELETE} batch removed, as a JSON array -- null for
	 * every other action. See V13 for why this, and only this, exists: it is the sole
	 * fallback CONTRACT.md names for a deleted feature, since deleted objects get no
	 * trash of their own.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "deleted_rows", columnDefinition = "jsonb", updatable = false)
	private String deletedRows;

	protected ChangeLogEntry() {
		// for JPA
	}

	public ChangeLogEntry(UUID projectId, UUID layerId, String layerName, String action,
			String clientName, int affectedCount, String deletedRows) {
		this.projectId = projectId;
		this.layerId = layerId;
		this.layerName = layerName;
		this.action = action;
		this.clientName = clientName;
		this.affectedCount = affectedCount;
		this.deletedRows = deletedRows;
	}

	public UUID getId() {
		return id;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public UUID getProjectId() {
		return projectId;
	}

	public UUID getLayerId() {
		return layerId;
	}

	public String getLayerName() {
		return layerName;
	}

	public String getAction() {
		return action;
	}

	public String getClientName() {
		return clientName;
	}

	public int getAffectedCount() {
		return affectedCount;
	}

	public String getDeletedRows() {
		return deletedRows;
	}
}
