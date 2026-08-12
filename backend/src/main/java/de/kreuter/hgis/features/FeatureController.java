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
	 * @param search free-text term matched, case-insensitively, against every text field
	 *     of the layer; combined with {@code filter} by AND when both are given, see
	 *     {@link TextSearch}
	 * @param bbox minLng,minLat,maxLng,maxLat in EPSG:4326
	 * @param mode exact geometry test against {@code bbox}: {@code intersects} or
	 *     {@code contains}; omitted keeps today's bounding-box-only comparison
	 * @param geometry include full-precision GeoJSON per feature
	 * @param cursor opaque position from the previous page's {@code nextCursor}
	 */
	@GetMapping("/api/layers/{layerId}/features")
	public FeatureDtos.Page list(
			@PathVariable UUID layerId,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false, defaultValue = "false") boolean desc,
			@RequestParam(required = false) String filter,
			@RequestParam(required = false) String search,
			@RequestParam(required = false) double[] bbox,
			@RequestParam(required = false) String mode,
			@RequestParam(required = false, defaultValue = "false") boolean geometry,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

		return service.list(layerId, new FeatureQueryService.Query(
				sort, desc, filter, search, bbox, mode, geometry, cursor, size));
	}

	/** One feature with all its attributes -- what Identify shows for a clicked geometry. */
	@GetMapping("/api/layers/{layerId}/features/{fid}")
	public FeatureDtos.Feature get(@PathVariable UUID layerId, @PathVariable long fid) {
		return service.get(layerId, fid);
	}

	/**
	 * The full fid set a filter/search restriction matches -- no geometry, no paging.
	 *
	 * <p>The bridge from a restriction to the selection store, and through it to the
	 * existing export: both already work with a fid list, but {@code list} above only ever
	 * exposes one page of it. Omitting both parameters returns every fid in the layer,
	 * subject to the same upper bound as any other restriction.
	 *
	 * @param filter expression over the layer's fields, see {@link FilterParser}
	 * @param search free-text term, see {@link TextSearch}
	 */
	@GetMapping("/api/layers/{layerId}/features/fids")
	public FeatureDtos.FidsResponse fids(
			@PathVariable UUID layerId,
			@RequestParam(required = false) String filter,
			@RequestParam(required = false) String search) {
		return service.fids(layerId, filter, search);
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
