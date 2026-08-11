package de.kreuter.hgis.ingest.dto;

import java.util.List;
import java.util.UUID;

/** Transport types for the import preview. Grouped the same way as JobDtos: small and
 *  only ever read together. */
public final class InspectionDtos {

	private InspectionDtos() {
	}

	/**
	 * Response for POST /api/projects/{projectId}/imports/inspect.
	 *
	 * @param uploadId    references the stored file; send it instead of the file to inspect
	 *                    the same upload again with a different encoding or CRS
	 * @param filename    the name the file was uploaded under, not the stored one
	 * @param featureCount null when the format does not know its total up front
	 * @param charset     null when the format leaves no room for a choice (GeoPackage,
	 *                    GeoJSON are UTF-8 by definition)
	 * @param extentWgs84 [minLng, minLat, maxLng, maxLat], always in WGS 84 whatever the
	 *                    source CRS is; null when nothing could be located
	 */
	public record Response(
			UUID uploadId,
			String filename,
			String geometryType,
			Long featureCount,
			String charset,
			int srid,
			String crsConfidence,
			List<Double> extentWgs84,
			List<Field> fields) {
	}

	/**
	 * @param dataType     the PostgreSQL type this field would get on import
	 * @param sampleValues first values in file order; a null entry is a null value, not an
	 *                     empty one
	 */
	public record Field(String name, String dataType, List<String> sampleValues) {
	}
}
