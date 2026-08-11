package de.kreuter.hgis.export;

import de.kreuter.hgis.export.GeoJsonExportService.Export;
import de.kreuter.hgis.export.dto.ExportDtos;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Downloads a layer as GeoJSON.
 *
 * <p>Two ways in, same resource and same response. GET is the honest shape for a
 * download -- a URL that can be pasted, bookmarked or opened in a new tab -- and it
 * carries the selection as {@code ?fids=1,2,3}. That works until the selection outgrows
 * the request line: the container caps the whole header block at 8 KB by default, which a
 * rubber-band selection of a few thousand features passes without trying, and the failure
 * arrives as a container-level rejection before any code of ours can explain it. POST
 * takes the same selection as a JSON body for exactly those cases.
 *
 * <p>The distinction between "no selection" and "an empty selection" survives in both:
 * omitting the parameter (or sending {@code fids: null}) exports the layer, sending an
 * empty one exports an empty collection. It is not a subtlety -- getting it wrong turns a
 * misclick into a download of the entire layer.
 */
@RestController
@RequestMapping("/api/layers/{layerId}")
public class ExportController {

	/** RFC 7946, section 12. Not application/json: the content is more specific than that. */
	private static final MediaType GEO_JSON = MediaType.parseMediaType("application/geo+json");

	private static final String EXTENSION = "geojson";

	/** An export is a snapshot of live data; a cached one is a wrong one. */
	private static final String CACHE_CONTROL = "no-store";

	private final GeoJsonExportService service;

	ExportController(GeoJsonExportService service) {
		this.service = service;
	}

	/**
	 * @param fids comma-separated row ids. Omit for the whole layer; pass empty
	 *             ({@code ?fids=}) for an empty collection.
	 */
	@GetMapping("/export.geojson")
	public ResponseEntity<StreamingResponseBody> exportGeoJson(
			@PathVariable UUID layerId,
			@RequestParam(required = false) String fids) {

		return download(service.prepare(layerId, FidSelection.parse(fids)));
	}

	/**
	 * Same export, for a selection too large to fit in a URL.
	 *
	 * <p>Bounded twice over: {@link ExportBodyLimitFilter} caps the body in bytes before
	 * it is parsed, {@link FidSelection#MAX_FIDS} caps the selection in rows once it is.
	 * The first exists because the second cannot run until the whole list is already in
	 * memory.
	 */
	@PostMapping(value = "/export.geojson", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<StreamingResponseBody> exportGeoJson(
			@PathVariable UUID layerId,
			@RequestBody ExportDtos.SelectionRequest request) {

		return download(service.prepare(layerId, request.toSelection()));
	}

	/**
	 * The layer is resolved before this is called, so a 404 is still a 404. Everything
	 * after the headers is written on the async thread, one feature at a time.
	 */
	private ResponseEntity<StreamingResponseBody> download(Export export) {
		return ResponseEntity.ok()
				.contentType(GEO_JSON)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ExportFilename.contentDisposition(export.layerName(), EXTENSION))
				.header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
				.body(out -> service.write(export, out));
	}
}
