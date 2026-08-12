package de.kreuter.hgis.tiles;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.LayerStyleService;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves rendered vector tiles.
 *
 * The URL is fully determined by the client from a layer's id, dataVersion, styleVersion
 * and clipVersion (see LayerController's DTOs), so a changed layer always produces a
 * different URL -- nothing ever needs to invalidate a cached tile, only bypass it via
 * the new URL. The optional {@code v} query parameter exists for exactly that purpose
 * on the client side; the server never reads it and always derives cache headers from
 * the layer's current, live state.
 *
 * clipVersion (CONTRACT.md phase 19) is what keeps that promise once a clip mask is in
 * play: a tile's content then also depends on which layer is marked as the project's
 * mask, that layer's geometry, and this layer's position relative to it -- none of
 * which the id/dataVersion/styleVersion triple could ever reflect on its own. It is
 * computed fresh from the current catalog on every request via
 * {@link Layer#clipVersion}, never stored, so a redrawn mask or a reordered layer takes
 * effect on the very next request without anything to invalidate.
 */
@RestController
@RequestMapping("/api/layers/{layerId}/tiles")
public class TileController {

	private static final MediaType MVT_MEDIA_TYPE =
			MediaType.parseMediaType("application/vnd.mapbox-vector-tile");

	/** The URL already carries the version, so a cached tile never goes stale. */
	private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

	private static final int MAX_ZOOM = 24;

	private final MvtService mvtService;
	private final LayerRepository layerRepository;
	private final LayerStyleService styleService;

	TileController(MvtService mvtService, LayerRepository layerRepository,
			LayerStyleService styleService) {
		this.mvtService = mvtService;
		this.layerRepository = layerRepository;
		this.styleService = styleService;
	}

	@GetMapping("/{z}/{x}/{y}.mvt")
	public ResponseEntity<byte[]> tile(
			@PathVariable UUID layerId,
			@PathVariable int z,
			@PathVariable int x,
			@PathVariable int y,
			@RequestParam(required = false) String v,
			@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

		validateCoordinates(z, x, y);

		Layer layer = layerRepository.findById(layerId)
				.orElseThrow(() -> new NotFoundException("Layer " + layerId + " existiert nicht"));

		// One extra, cheap lookup per tile request (one row at most, per project) so the
		// cache headers below always reflect whether a mask currently affects this layer
		// -- see CONTRACT.md phase 19. It runs ahead of the expensive render, same as the
		// rest of this method's cache-relevant state.
		Layer maskLayer = layerRepository.findClipMask(layer.getProject().getId()).orElse(null);
		long clipVersion = layer.clipVersion(maskLayer);

		String etag = quotedETag(layer, clipVersion);
		if (etag.equals(ifNoneMatch == null ? null : ifNoneMatch.trim())) {
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
					.header(HttpHeaders.ETAG, etag)
					.header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
					.build();
		}

		// After the ETag check, not before: a client that already holds this tile gets its
		// 304 without the style ever being read, let alone its fields looked up.
		String maskTableName = layer.isClippedBy(maskLayer) ? maskLayer.getTableName() : null;
		byte[] mvt = mvtService.renderTile(layer.getTableName(), layer.getSrid(),
				styleService.tileColumns(layer), maskTableName, z, x, y);

		ResponseEntity.BodyBuilder response = ResponseEntity
				.status(mvt == null ? HttpStatus.NO_CONTENT : HttpStatus.OK)
				.header(HttpHeaders.ETAG, etag)
				.header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);

		if (mvt == null) {
			return response.build();
		}
		return response.contentType(MVT_MEDIA_TYPE).body(mvt);
	}

	/** z is a Web Mercator zoom level; x/y must address a tile that actually exists within it. */
	private static void validateCoordinates(int z, int x, int y) {
		if (z < 0 || z > MAX_ZOOM) {
			throw new BadRequestException("z muss zwischen 0 und " + MAX_ZOOM + " liegen. Wert war " + z + ".");
		}
		long tilesPerAxis = 1L << z;
		if (x < 0 || x >= tilesPerAxis || y < 0 || y >= tilesPerAxis) {
			throw new BadRequestException("x/y liegen außerhalb des gültigen Bereichs für z=" + z);
		}
	}

	private static String quotedETag(Layer layer, long clipVersion) {
		return "\"" + layer.getId() + "-" + layer.getDataVersion() + "-" + layer.getStyleVersion()
				+ "-" + clipVersion + "\"";
	}
}
