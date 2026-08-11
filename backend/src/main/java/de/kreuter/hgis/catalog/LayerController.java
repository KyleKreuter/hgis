package de.kreuter.hgis.catalog;

import de.kreuter.hgis.catalog.dto.ClassificationDtos;
import de.kreuter.hgis.catalog.dto.LayerDtos;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LayerController {

	private final LayerService service;
	private final ClassificationService classificationService;

	LayerController(LayerService service, ClassificationService classificationService) {
		this.service = service;
		this.classificationService = classificationService;
	}

	@GetMapping("/api/projects/{projectId}/layers")
	public List<LayerDtos.Summary> list(@PathVariable UUID projectId) {
		return service.listByProject(projectId);
	}

	@PostMapping("/api/projects/{projectId}/layers")
	public ResponseEntity<LayerDtos.Summary> create(@PathVariable UUID projectId,
			@Valid @RequestBody LayerDtos.CreateRequest request) {
		LayerDtos.Summary created = service.create(projectId, request);
		return ResponseEntity.created(URI.create("/api/layers/" + created.id())).body(created);
	}

	@GetMapping("/api/layers/{layerId}")
	public LayerDtos.Detail get(@PathVariable UUID layerId) {
		return service.get(layerId);
	}

	@PatchMapping("/api/layers/{layerId}")
	public LayerDtos.Detail update(@PathVariable UUID layerId,
			@Valid @RequestBody LayerDtos.UpdateRequest request) {
		return service.update(layerId, request);
	}

	/**
	 * Class boundaries for a graduated renderer.
	 *
	 * @param field  a field of this layer, by source name or column name; has to be numeric
	 * @param method quantile, equalInterval or naturalBreaks
	 * @param classes 2 to 12
	 */
	@GetMapping("/api/layers/{layerId}/classify")
	public ClassificationDtos.Breaks classify(
			@PathVariable UUID layerId,
			@RequestParam String field,
			@RequestParam(required = false, defaultValue = "quantile") String method,
			@RequestParam(required = false, defaultValue = "5") int classes) {
		return classificationService.classify(layerId, field, method, classes);
	}

	/**
	 * The values a categorized renderer would have to cover, most frequent first.
	 *
	 * @param limit how many distinct values to return; the response says whether there
	 *              are more, which is usually the more interesting answer
	 */
	@GetMapping("/api/layers/{layerId}/values")
	public ClassificationDtos.Values values(
			@PathVariable UUID layerId,
			@RequestParam String field,
			@RequestParam(required = false) Integer limit) {
		return classificationService.values(layerId, field, limit);
	}

	/**
	 * PUT rather than PATCH: the body is the complete new order, not a delta, and
	 * sending it twice has to leave the same result.
	 */
	@PutMapping("/api/projects/{projectId}/layers/order")
	public List<LayerDtos.Summary> reorder(@PathVariable UUID projectId,
			@Valid @RequestBody LayerDtos.ReorderRequest request) {
		return service.reorder(projectId, request.layerIdsBottomToTop());
	}

	@DeleteMapping("/api/layers/{layerId}")
	public ResponseEntity<Void> delete(@PathVariable UUID layerId) {
		service.delete(layerId);
		return ResponseEntity.noContent().build();
	}
}
