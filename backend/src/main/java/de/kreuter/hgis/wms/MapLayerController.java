package de.kreuter.hgis.wms;

import de.kreuter.hgis.catalog.dto.LayerDtos;
import de.kreuter.hgis.common.ClientId;
import de.kreuter.hgis.wms.dto.MapLayerDtos;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * CONTRACT.md 3. No job: nothing is downloaded, so the layer exists and the response is
 * {@code 201} by the time this method returns -- unlike {@code ingest.ImportController}
 * and {@code geoportal.GeoportalImportController}, which both answer {@code 202} with a
 * job to poll.
 */
@RestController
class MapLayerController {

	private final MapLayerService service;

	MapLayerController(MapLayerService service) {
		this.service = service;
	}

	@PostMapping("/api/projects/{projectId}/map-layers")
	public ResponseEntity<LayerDtos.Summary> create(@PathVariable UUID projectId,
			@Valid @RequestBody MapLayerDtos.CreateRequest request,
			@RequestHeader(name = ClientId.HEADER, required = false) String origin) {
		LayerDtos.Summary created = service.create(projectId, request, ClientId.require(origin));
		return ResponseEntity.created(URI.create("/api/layers/" + created.id())).body(created);
	}
}
