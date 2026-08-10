package de.kreuter.hgis.catalog;

import de.kreuter.hgis.catalog.dto.ProjectDtos;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

	@GetMapping
	public List<ProjectDtos.Summary> list() {
		return service.list();
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
}
