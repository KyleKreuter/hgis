package de.kreuter.hgis.changelog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeLogRepository extends JpaRepository<ChangeLogEntry, UUID> {

	/**
	 * Newest first. {@code id} is the tiebreaker for entries sharing one {@code
	 * occurred_at} instant -- {@code CreationTimestamp} has millisecond resolution and an
	 * edit batch can write several rows well inside one millisecond, so ordering by
	 * {@code occurredAt} alone would leave their relative order to the database's whim.
	 * UUIDv7 sorts chronologically by construction (see {@code Uuid7}), so this stays a
	 * true insertion order even for entries sharing an instant.
	 */
	List<ChangeLogEntry> findByProjectIdOrderByOccurredAtDescIdDesc(UUID projectId, Pageable pageable);
}
