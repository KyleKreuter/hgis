package de.kreuter.hgis.catalog;

import de.kreuter.hgis.catalog.dto.ClassificationDtos;
import de.kreuter.hgis.catalog.dto.LayerDtos;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
	private final LayerFieldService fieldService;
	private final ClassificationService classificationService;

	LayerController(LayerService service, LayerFieldService fieldService,
			ClassificationService classificationService) {
		this.service = service;
		this.fieldService = fieldService;
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

	/** Adds one attribute field to an existing layer (CONTRACT.md phase 11). */
	@PostMapping("/api/layers/{layerId}/fields")
	public ResponseEntity<LayerDtos.Field> addField(@PathVariable UUID layerId,
			@Valid @RequestBody LayerDtos.AddFieldRequest request) {
		LayerDtos.Field field = fieldService.addField(layerId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(field);
	}

	/** Renames an existing field's display name; column and type are immutable. */
	@PatchMapping("/api/layers/{layerId}/fields/{fieldId}")
	public LayerDtos.Field renameField(@PathVariable UUID layerId, @PathVariable UUID fieldId,
			@Valid @RequestBody LayerDtos.RenameFieldRequest request) {
		return fieldService.renameField(layerId, fieldId, request);
	}

	/**
	 * What deleting this field would touch -- data for the confirmation dialog, not a
	 * check of its own (CONTRACT.md phase 12).
	 */
	@GetMapping("/api/layers/{layerId}/fields/{fieldId}/usage")
	public LayerDtos.FieldUsage usage(@PathVariable UUID layerId, @PathVariable UUID fieldId) {
		return fieldService.usage(layerId, fieldId);
	}

	/**
	 * Deletes an attribute field: drops its column and, if the style classified or
	 * labelled by it, resets that part of the style so it stays valid (CONTRACT.md
	 * phase 12).
	 */
	@DeleteMapping("/api/layers/{layerId}/fields/{fieldId}")
	public ResponseEntity<Void> deleteField(@PathVariable UUID layerId, @PathVariable UUID fieldId) {
		fieldService.deleteField(layerId, fieldId);
		return ResponseEntity.noContent().build();
	}
}
