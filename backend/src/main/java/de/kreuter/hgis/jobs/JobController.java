package de.kreuter.hgis.jobs;

import de.kreuter.hgis.jobs.dto.JobDtos;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

	private final JobService service;

	JobController(JobService service) {
		this.service = service;
	}

	@GetMapping("/{jobId}")
	public JobDtos.Response get(@PathVariable UUID jobId) {
		return service.get(jobId);
	}
}
