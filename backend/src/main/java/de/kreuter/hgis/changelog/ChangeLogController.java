package de.kreuter.hgis.changelog;

import de.kreuter.hgis.changelog.dto.ChangeLogDtos;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChangeLogController {

	private static final int DEFAULT_SIZE = 200;

	private final ChangeLogService service;

	ChangeLogController(ChangeLogService service) {
		this.service = service;
	}

	/**
	 * The write change log for one project, newest first (CONTRACT.md "Schreibstufe" 1.2).
	 *
	 * @param size ceiling on entries returned; between 1 and 1000, defaults to 200
	 * @param includeDeletedRows whether to include each {@code feature.delete} entry's
	 *     captured rows -- see {@link ChangeLogService#list}
	 */
	@GetMapping("/api/projects/{projectId}/changes")
	public List<ChangeLogDtos.Entry> list(
			@PathVariable UUID projectId,
			@RequestParam(required = false, defaultValue = "" + DEFAULT_SIZE) int size,
			@RequestParam(required = false, defaultValue = "false") boolean includeDeletedRows) {
		return service.list(projectId, size, includeDeletedRows);
	}
}
