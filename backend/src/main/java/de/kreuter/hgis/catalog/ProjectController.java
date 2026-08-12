package de.kreuter.hgis.catalog;

import de.kreuter.hgis.catalog.dto.ProjectDtos;
import de.kreuter.hgis.jobs.dto.JobDtos;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

	private final ProjectService service;

	ProjectController(ProjectService service) {
		this.service = service;
	}

	/**
	 * One page of the project browser, most recently opened first.
	 *
	 * @param cursor opaque position from the previous page's {@code nextCursor}
	 * @param limit  page size, between 1 and 100; defaults to 24
	 * @param q      matched against name and description, case-insensitively and in
	 *               word parts
	 */
	@GetMapping
	public ProjectDtos.Page list(
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false, defaultValue = "24") int limit,
			@RequestParam(required = false) String q) {
		return service.list(q, cursor, limit);
	}

	@PostMapping
	public ResponseEntity<ProjectDtos.Detail> create(
			@Valid @RequestBody ProjectDtos.CreateRequest request) {
		ProjectDtos.Detail created = service.create(request);
		return ResponseEntity.created(URI.create("/api/projects/" + created.id())).body(created);
	}

	/**
	 * @param open whether this read counts as opening the project. The browser reads
	 *             projects for its list without disturbing the recently-opened order,
	 *             so the timestamp is only touched when the workspace actually loads.
	 */
	@GetMapping("/{id}")
	public ProjectDtos.Detail get(@PathVariable UUID id,
			@RequestParam(defaultValue = "false") boolean open) {
		return open ? service.open(id) : service.get(id);
	}

	@PatchMapping("/{id}")
	public ProjectDtos.Detail update(@PathVariable UUID id,
			@Valid @RequestBody ProjectDtos.UpdateRequest request) {
		return service.update(id, request);
	}

	@PostMapping("/{id}/duplicate")
	public ResponseEntity<JobDtos.Response> duplicate(@PathVariable UUID id,
			@Valid @RequestBody(required = false) ProjectDtos.DuplicateRequest request) {
		JobDtos.Response job = service.duplicate(id,
				request == null ? new ProjectDtos.DuplicateRequest(null) : request);
		return ResponseEntity.accepted().body(job);
	}

	/** Preflight for the delete dialog: how much would actually be destroyed. */
	@GetMapping("/{id}/deletion-impact")
	public ProjectDtos.DeletionImpact deletionImpact(@PathVariable UUID id) {
		return service.deletionImpact(id);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}

	/** The client's saved view state: active layer, and per-layer sort, query and selection. */
	@GetMapping("/{id}/view-state")
	public ProjectDtos.ViewState viewState(@PathVariable UUID id) {
		return service.viewState(id);
	}

	/** Replaces the saved view state wholesale; there is no partial update. */
	@PutMapping("/{id}/view-state")
	public ResponseEntity<Void> updateViewState(@PathVariable UUID id,
			@RequestBody ProjectDtos.ViewState request) {
		service.updateViewState(id, request);
		return ResponseEntity.noContent().build();
	}
}
