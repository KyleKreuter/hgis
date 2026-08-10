package de.kreuter.hgis.ingest.reader;

import de.kreuter.hgis.ingest.spi.SourceReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Detects the format of an uploaded source file from its name and opens the matching
 * {@link SourceReader}. This is the only class outside the format-specific readers that
 * needs to know how many formats exist.
 */
public final class SourceReaderFactory {

	private SourceReaderFactory() {
	}

	/**
	 * @param file            the uploaded file: a ZIP for a Shapefile set, or a single
	 *                        .gpkg/.geojson/.json/.csv file
	 * @param sridOverride    user-supplied CRS; takes precedence over any detection
	 * @param charsetOverride user-supplied encoding; takes precedence over any detection.
	 *                        Only Shapefile and CSV can be anything but UTF-8, so this is
	 *                        ignored for GeoPackage and GeoJSON.
	 */
	public static SourceReader open(Path file, Integer sridOverride, Charset charsetOverride) {
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".zip")) {
			return new ShapefileSourceReader(file, sridOverride, charsetOverride);
		}
		if (name.endsWith(".gpkg")) {
			return new GeoPackageSourceReader(file, sridOverride);
		}
		if (name.endsWith(".geojson") || name.endsWith(".json")) {
			return new GeoJsonSourceReader(file, sridOverride);
		}
		if (name.endsWith(".csv")) {
			return new CsvSourceReader(file, sridOverride, charsetOverride);
		}
		throw new UnsupportedSourceFormatException("Nicht unterstütztes Dateiformat: " + file.getFileName());
	}
}
