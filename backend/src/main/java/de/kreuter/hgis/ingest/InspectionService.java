package de.kreuter.hgis.ingest;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.TypeMapper;
import de.kreuter.hgis.ingest.UploadStorage.StoredUpload;
import de.kreuter.hgis.ingest.dto.InspectionDtos;
import de.kreuter.hgis.ingest.reader.SourceReaderFactory;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceReader;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Answers "what would this import produce?" without producing it.
 *
 * <p>A wrong CRS is the most expensive import mistake there is: nothing about it looks
 * like an error, and it only surfaces once the data is in the database and the map is
 * empty. The same goes for a misread encoding, which turns every umlaut in a street name
 * into noise that a later fix cannot undo. Both are cheap to see and hard to describe, so
 * this hands the user what they can judge for themselves -- real values out of the file,
 * and a bounding box they can recognise on a map -- instead of a detector's verdict.
 */
@Service
public class InspectionService {

	private static final Logger log = LoggerFactory.getLogger(InspectionService.class);

	/**
	 * Formats where the encoding is a real choice. GeoPackage and GeoJSON are UTF-8 by
	 * specification, so the readers report UTF-8 for them regardless -- reporting that as
	 * a detection result would offer the user a decision they cannot make.
	 */
	private static final Set<String> CHARSET_RELEVANT_EXTENSIONS = Set.of("zip", "csv");

	private final JdbcClient jdbc;

	InspectionService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Opens a reader for a stored upload, with reader failures translated into the error
	 * the user will see. Shared with the import endpoint so both report an unknown format,
	 * a broken file or an implausible CRS in exactly the same words.
	 *
	 * @throws BadRequestException on anything the file itself is to blame for
	 */
	public SourceReader open(StoredUpload upload, Integer srid, Charset charset) {
		try {
			return SourceReaderFactory.open(upload.file(), srid, charset);
		}
		catch (BadRequestException ex) {
			throw new BadRequestException(upload.withOriginalName(ex.getMessage()));
		}
		catch (RuntimeException ex) {
			log.warn("Could not open uploaded file {} ({})", upload.originalFilename(), upload.id(), ex);
			throw new BadRequestException("Das Programm kann die Datei nicht lesen: "
					+ upload.withOriginalName(ex.getMessage()));
		}
	}

	public InspectionDtos.Response inspect(StoredUpload upload, Integer srid, Charset charset) {
		try (SourceReader reader = open(upload, srid, charset)) {
			SourceSchema schema = reader.schema();
			// Before the sample rather than after: whether the CRS can be used at all must
			// not depend on how many features happened to be readable.
			requireKnownSrid(schema.sourceSrid());
			FeatureSample.Result sample = FeatureSample.collect(reader, schema.fields());

			return new InspectionDtos.Response(
					upload.id(),
					upload.originalFilename(),
					schema.geometryType().name(),
					schema.featureCount(),
					reportedCharset(upload, schema),
					schema.sourceSrid(),
					schema.crsConfidence().name(),
					toWgs84(sample.bbox(), schema.sourceSrid()),
					toFields(schema.fields(), sample));
		}
	}

	private static String reportedCharset(StoredUpload upload, SourceSchema schema) {
		String extension = UploadStorage.extensionOf(upload.originalFilename());
		return CHARSET_RELEVANT_EXTENSIONS.contains(extension) ? schema.charset() : null;
	}

	private static List<InspectionDtos.Field> toFields(List<SourceField> fields, FeatureSample.Result sample) {
		List<InspectionDtos.Field> result = new ArrayList<>(fields.size());
		for (SourceField field : fields) {
			result.add(new InspectionDtos.Field(
					field.name(),
					TypeMapper.toPostgresType(field.javaType()),
					sample.valuesByField().getOrDefault(field.name(), List.of())));
		}
		return result;
	}

	/**
	 * Projects the sampled bounding box into WGS 84 so the frontend can say where the data
	 * is without knowing a single CRS.
	 *
	 * <p>Done in PostGIS rather than in Java on purpose: the import reprojects with
	 * {@code ST_Transform} too, so a preview computed any other way could show a placement
	 * the import then does not reproduce. Only the corners travel through the transform --
	 * for locating a dataset on a map that is exact enough, and it costs one round trip
	 * instead of one per sampled feature.
	 *
	 * @return [minLng, minLat, maxLng, maxLat], or null when nothing was sampled
	 */
	private List<Double> toWgs84(Envelope bbox, int srid) {
		if (bbox == null || bbox.isNull()) {
			return null;
		}
		return jdbc.sql("""
				SELECT ST_XMin(box) AS min_lng, ST_YMin(box) AS min_lat,
				       ST_XMax(box) AS max_lng, ST_YMax(box) AS max_lat
				FROM (
				    SELECT ST_Transform(
				               ST_MakeEnvelope(:minX, :minY, :maxX, :maxY, :srid), 4326) AS box
				) AS transformed
				""")
				.param("minX", bbox.getMinX())
				.param("minY", bbox.getMinY())
				.param("maxX", bbox.getMaxX())
				.param("maxY", bbox.getMaxY())
				.param("srid", srid)
				.query((rs, rowNum) -> List.of(
						rs.getDouble("min_lng"), rs.getDouble("min_lat"),
						rs.getDouble("max_lng"), rs.getDouble("max_lat")))
				.single();
	}

	/**
	 * A code PostGIS does not carry cannot be transformed -- neither here nor during the
	 * import, which would fail with the same data hours later. Saying so while the user is
	 * still looking at the dialog is the whole point of this endpoint.
	 */
	private void requireKnownSrid(int srid) {
		boolean known = Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM spatial_ref_sys WHERE srid = :srid)")
				.param("srid", srid)
				.query(Boolean.class)
				.single());
		if (!known) {
			throw new BadRequestException("EPSG:" + srid
					+ " ist der Datenbank nicht bekannt. Eine Umprojektion in dieses System ist nicht möglich.");
		}
	}
}
