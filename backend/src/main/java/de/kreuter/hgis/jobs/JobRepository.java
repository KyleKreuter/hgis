package de.kreuter.hgis.jobs;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, UUID> {

	/**
	 * Jobs still RUNNING. Only meaningful right after startup: nothing in this
	 * application keeps a job RUNNING across a restart, so any row found here was
	 * orphaned by a crash and belongs to {@code JobJanitor}.
	 */
	List<Job> findByStatus(Job.Status status);
}
