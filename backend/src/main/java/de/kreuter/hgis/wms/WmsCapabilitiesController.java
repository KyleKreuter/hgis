package de.kreuter.hgis.wms;

import de.kreuter.hgis.wms.dto.WmsDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CONTRACT.md 2. */
@RestController
class WmsCapabilitiesController {

	private final WmsCapabilitiesService service;

	WmsCapabilitiesController(WmsCapabilitiesService service) {
		this.service = service;
	}

	@GetMapping("/api/wms/capabilities")
	public WmsDtos.CapabilitiesResponse capabilities(@RequestParam String url) {
		return service.capabilities(url);
	}
}
