package de.kreuter.hgis.jobs.dto;

import java.time.Instant;
import java.util.UUID;

/** Transport type for the job API. Grouped the same way as ProjectDtos: small and only ever
 *  read together. */
public final class JobDtos {

	private JobDtos() {
	}

	/** Response for GET /api/jobs/{jobId}, matches the "Job" shape in the API contract. */
	public record Response(
			UUID id,
			String type,
			String status,
			String filename,
			long processedCount,
			Long totalCount,
			long skippedCount,
			UUID outputLayerId,
			UUID outputProjectId,
			String message,
			Instant startedAt,
			Instant finishedAt,
			Instant createdAt) {
	}
}
