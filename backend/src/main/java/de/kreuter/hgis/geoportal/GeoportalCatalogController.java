package de.kreuter.hgis.geoportal;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.geoportal.dto.GeoportalDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CONTRACT.md 11.2 through 11.5.
 *
 * <p>{@code id} is opaque and, for a dataset backed by OGC API Features, contains a literal
 * {@code /} (CONTRACT.md's own example: {@code strassenbaumkataster/strassenbaumkataster_hh}
 * -- see {@link CatalogLoader}). That collides with how Spring routes a path: its {@code
 * PathPattern} matcher, unlike the legacy {@code AntPathMatcher}, never lets a single
 * template variable cross a {@code /} -- not even one written as the regex form {@code
 * {id:.+}}, which still only matches within one segment. Only a trailing catch-all,
 * {@code {*id}}, absorbs the rest of the path, and a catch-all has to be the pattern's last
 * element, which rules out combining it with a literal {@code /count} suffix the way
 * CONTRACT.md 11.5 asks for.
 *
 * <p>The two GET mappings are therefore one method: a single {@code {*id}} catch-all reads
 * whether the captured path ends in {@code /count} and dispatches to {@link
 * GeoportalDatasetService#count} or {@link GeoportalDatasetService#detail} itself, rather
 * than leaving that decision to two patterns Spring cannot actually tell apart. Verified in
 * {@code GeoportalCatalogControllerTest}, including that a dataset id can never itself be
 * mistaken for the {@code /count} suffix it might end in.
 */
@RestController
class GeoportalCatalogController {

	private static final String COUNT_SUFFIX = "/count";

	private final GeoportalDatasetService service;

	GeoportalCatalogController(GeoportalDatasetService service) {
		this.service = service;
	}

	@GetMapping("/api/geoportal/datasets")
	public GeoportalDtos.CatalogResponse list() {
		return service.list();
	}

	@PostMapping("/api/geoportal/catalog/refresh")
	public GeoportalDtos.CatalogResponse refresh() {
		return service.refresh();
	}

	/**
	 * CONTRACT.md 11.4 and 11.5 together -- see the class Javadoc for why. The return type
	 * is deliberately {@code Object}: Jackson converts whichever concrete record comes back
	 * by its runtime type, exactly as it would from two separate {@code @GetMapping} methods.
	 */
	@GetMapping("/api/geoportal/datasets/{*id}")
	public Object detailOrCount(@PathVariable String id, @RequestParam(required = false) String bbox) {
		// {*id} captures with a leading slash (e.g. "/strassenbaumkataster/strassenbaumkataster_hh").
		String path = id.startsWith("/") ? id.substring(1) : id;

		if (path.length() > COUNT_SUFFIX.length() && path.endsWith(COUNT_SUFFIX)) {
			String datasetId = path.substring(0, path.length() - COUNT_SUFFIX.length());
			if (bbox == null || bbox.isBlank()) {
				// CONTRACT.md 11.5: "bbox is required here; without one, 11.4 already has the number."
				throw new BadRequestException("bbox ist erforderlich: minLng,minLat,maxLng,maxLat");
			}
			return service.count(datasetId, parseBbox(bbox));
		}
		return service.detail(path);
	}

	private static double[] parseBbox(String raw) {
		String[] parts = raw.split(",");
		if (parts.length != 4) {
			throw new BadRequestException("bbox muss vier durch Komma getrennte Zahlen enthalten: minLng,minLat,maxLng,maxLat");
		}
		try {
			return new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
					Double.parseDouble(parts[2]), Double.parseDouble(parts[3]) };
		}
		catch (NumberFormatException e) {
			throw new BadRequestException("bbox enthält keine gültigen Zahlen: " + raw);
		}
	}
}
