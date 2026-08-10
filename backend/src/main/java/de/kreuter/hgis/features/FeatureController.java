package de.kreuter.hgis.features;

import de.kreuter.hgis.features.dto.EditDtos;
import de.kreuter.hgis.features.dto.FeatureDtos;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeatureController {

	private static final int DEFAULT_PAGE_SIZE = 200;

	private final FeatureQueryService service;
	private final EditService editService;

	FeatureController(FeatureQueryService service, EditService editService) {
		this.service = service;
		this.editService = editService;
	}

	/**
	 * A page of a layer's rows.
	 *
	 * @param sort field to sort by, by source name; fid when omitted
	 * @param filter expression over the layer's fields, see {@link FilterParser}
	 * @param bbox minLng,minLat,maxLng,maxLat in EPSG:4326
	 * @param geometry include full-precision GeoJSON per feature
	 * @param cursor opaque position from the previous page's {@code nextCursor}
	 */
	@GetMapping("/api/layers/{layerId}/features")
	public FeatureDtos.Page list(
			@PathVariable UUID layerId,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false, defaultValue = "false") boolean desc,
			@RequestParam(required = false) String filter,
			@RequestParam(required = false) double[] bbox,
			@RequestParam(required = false, defaultValue = "false") boolean geometry,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

		return service.list(layerId,
				new FeatureQueryService.Query(sort, desc, filter, bbox, geometry, cursor, size));
	}

	/** One feature with all its attributes -- what Identify shows for a clicked geometry. */
	@GetMapping("/api/layers/{layerId}/features/{fid}")
	public FeatureDtos.Feature get(@PathVariable UUID layerId, @PathVariable long fid) {
		return service.get(layerId, fid);
	}

	/**
	 * Applies a batch of edits in one transaction.
	 *
	 * <p>POST rather than PATCH on the features: the body is not a modified feature but a
	 * list of operations, and its effect on the collection is a creation as much as a
	 * change.
	 */
	@PostMapping("/api/layers/{layerId}/edits")
	public EditDtos.Response edit(@PathVariable UUID layerId,
			@Valid @RequestBody EditDtos.Request request) {
		return editService.apply(layerId, request);
	}
}
