package de.kreuter.hgis.catalog;

import de.kreuter.hgis.catalog.dto.LayerDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LayerController {

	private final LayerService service;

	LayerController(LayerService service) {
		this.service = service;
	}

	@GetMapping("/api/projects/{projectId}/layers")
	public List<LayerDtos.Summary> list(@PathVariable UUID projectId) {
		return service.listByProject(projectId);
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
