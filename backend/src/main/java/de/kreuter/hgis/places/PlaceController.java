package de.kreuter.hgis.places;

import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import de.kreuter.hgis.jobs.dto.JobDtos;
import de.kreuter.hgis.places.dto.PlaceDtos;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The place search endpoints (CONTRACT.md "API-Contract: Ortssuche"). Unlike every other
 * controller in the application, neither endpoint here is scoped to a project -- Hamburg's
 * street index and the Photon lookup are both global, not per-project data.
 */
@RestController
public class PlaceController {

	private static final Logger log = LoggerFactory.getLogger(PlaceController.class);

	private final PlaceSearchService searchService;
	private final PlaceRefreshService refreshService;
	private final JobService jobService;

	PlaceController(PlaceSearchService searchService, PlaceRefreshService refreshService, JobService jobService) {
		this.searchService = searchService;
		this.refreshService = refreshService;
		this.jobService = jobService;
	}

	@GetMapping("/api/places")
	public PlaceDtos.Response search(
			@RequestParam String q,
			@RequestParam(required = false) Integer limit) {
		return searchService.search(q, limit);
	}

	@PostMapping("/api/places/refresh")
	public ResponseEntity<JobDtos.Response> refresh() {
		// No project: Hamburg's street index is a single, application-wide table, not
		// per-project data (V10__place.sql). The job table's own project_id column is
		// nullable for exactly this case.
		Job job = jobService.create(null, Job.Type.PROCESSING, "Hamburg-Orte");
		try {
			refreshService.refreshAsync(job.getId());
		}
		catch (RejectedExecutionException ex) {
			jobService.markFailed(job.getId(),
					"Es läuft bereits zu viel im Hintergrund. Starten Sie den Abzug in einem Moment erneut.");
			log.warn("Place refresh {} rejected, import pool is saturated: {}", job.getId(), ex.getMessage());
			throw ex;
		}
		return ResponseEntity.accepted().body(jobService.get(job.getId()));
	}
}
