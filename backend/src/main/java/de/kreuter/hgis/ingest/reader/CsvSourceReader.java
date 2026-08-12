package de.kreuter.hgis.ingest.reader;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceField;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

/**
 * Reads CSV. Nothing about the format is declared -- delimiter, decimal convention and
 * even whether a row has coordinates or a geometry column at all are all sniffed from the
 * content, which is why this reader carries more inference logic than the others.
 *
 * The CRS is always guessed (never declared or assumed): CSV has no convention to fall
 * back on the way GeoJSON does, so every CSV import is a candidate for confirmation.
 */
final class CsvSourceReader extends AbstractSourceReader {

	private static final int SAMPLE_SIZE = 1000;
	private static final char[] DELIMITER_CANDIDATES = {';', ',', '\t', '|'};

	private static final Set<String> X_CANDIDATES = Set.of("x", "lon", "long", "longitude", "rechtswert", "easting");
	private static final Set<String> Y_CANDIDATES = Set.of("y", "lat", "latitude", "hochwert", "northing");
	private static final Set<String> WKT_CANDIDATES = Set.of("wkt", "geom", "geometry");

	private static final Pattern DOT_DECIMAL = Pattern.compile("-?\\d+\\.\\d+");
	private static final Pattern COMMA_DECIMAL = Pattern.compile("-?\\d{1,3}(\\.\\d{3})*,\\d+|-?\\d+,\\d+");
	private static final Pattern INTEGER = Pattern.compile("-?\\d+");

	private final Path file;
	private final Charset charset;
	private final char delimiter;
	private final boolean decimalComma;
	private final String[] header;
	private final int xColumn;
	private final int yColumn;
	private final int wktColumn;
	private final List<SourceField> fields;
	private final SourceSchema schema;
	private final GeometryFactory geometryFactory = new GeometryFactory();
	private final WKTReader wktReader = new WKTReader(geometryFactory);

	CsvSourceReader(Path file, Integer sridOverride, Charset charsetOverride) {
		this.file = file;
		this.charset = CharsetDetector.detectForCsv(file, charsetOverride);

		List<String> sniffLines = readRawLines(file, charset, 50);
		if (sniffLines.isEmpty()) {
			throw new SourceReadException("CSV-Datei ist leer: " + file.getFileName());
		}
		this.delimiter = detectDelimiter(sniffLines);

		try (CSVReader csvReader = openCsvReader()) {
			String[] headerRow = csvReader.readNextSilently();
			if (headerRow == null) {
				throw new SourceReadException("CSV-Datei hat keine Kopfzeile: " + file.getFileName());
			}
			this.header = headerRow;

			GeometryColumns columns = locateGeometryColumns(headerRow, file);
			this.xColumn = columns.x();
			this.yColumn = columns.y();
			this.wktColumn = columns.wkt();

			AttributeTypeInference attributeTypes = new AttributeTypeInference();
			for (int i = 0; i < header.length; i++) {
				if (i == xColumn || i == yColumn || i == wktColumn) {
					continue;
				}
				attributeTypes.observe(header[i].strip(), null);
			}

			List<String[]> sampleRows = new ArrayList<>();
			String[] row;
			while (sampleRows.size() < SAMPLE_SIZE && (row = csvReader.readNextSilently()) != null) {
				if (row.length == header.length) {
					sampleRows.add(row);
				}
			}
			this.decimalComma = detectDecimalComma(sampleRows);

			List<Geometry> sampledGeometries = new ArrayList<>(sampleRows.size());
			for (String[] sampleRow : sampleRows) {
				Geometry geometry = toGeometry(sampleRow);
				if (geometry != null) {
					sampledGeometries.add(geometry);
				}
				for (int i = 0; i < header.length; i++) {
					if (i == xColumn || i == yColumn || i == wktColumn) {
						continue;
					}
					attributeTypes.observe(header[i].strip(), inferCellValue(sampleRow[i]));
				}
			}
			this.fields = attributeTypes.fields();

			FeatureSampling.Sample sample = FeatureSampling.sample(sampledGeometries.iterator(), sampledGeometries.size());
			CrsDetector.Detection crs = sridOverride != null
					? CrsDetector.declared(sridOverride)
					: CrsDetector.guess(sample.bbox());

			this.schema = new SourceSchema(sample.geometryType(), crs.srid(), fields, charset.name(), crs.confidence(), null);
		} catch (IOException e) {
			throw new SourceReadException("Der Import kann die CSV-Datei nicht lesen: " + file, e);
		}
	}

	private record GeometryColumns(int x, int y, int wkt) {
	}

	private static GeometryColumns locateGeometryColumns(String[] header, Path file) {
		int x = -1;
		int y = -1;
		int wkt = -1;
		for (int i = 0; i < header.length; i++) {
			String name = header[i].strip().toLowerCase(Locale.ROOT);
			if (x == -1 && X_CANDIDATES.contains(name)) {
				x = i;
			} else if (y == -1 && Y_CANDIDATES.contains(name)) {
				y = i;
			} else if (wkt == -1 && WKT_CANDIDATES.contains(name)) {
				wkt = i;
			}
		}
		if (!((x != -1 && y != -1) || wkt != -1)) {
			throw new SourceReadException(
					"Keine Geometriespalte gefunden (erwartet x/y, lon/lat, rechtswert/hochwert, "
							+ "easting/northing oder eine WKT-Spalte): " + file.getFileName());
		}
		return new GeometryColumns(x, y, wkt);
	}

	@Override
	public SourceSchema schema() {
		return schema;
	}

	@Override
	public Stream<SourceFeature> features() {
		CSVReader csvReader;
		try {
			csvReader = openCsvReader();
			csvReader.readNextSilently(); // skip the header
		} catch (IOException e) {
			throw new SourceReadException("Der Import kann die CSV-Datei nicht lesen: " + file, e);
		}
		Iterator<SourceFeature> iterator = new Iterator<>() {
			private SourceFeature pending = advance();

			private SourceFeature advance() {
				while (true) {
					String[] row;
					try {
						row = csvReader.readNextSilently();
					} catch (IOException e) {
						throw new SourceReadException("Der Import kann die CSV-Datei nicht vollständig lesen", e);
					}
					if (row == null) {
						return null;
					}
					if (row.length != header.length) {
						recordSkip();
						continue;
					}
					Geometry geometry = toGeometry(row);
					if (geometry == null || geometry.isEmpty()) {
						recordSkip();
						continue;
					}
					Map<String, Object> attributes = new LinkedHashMap<>();
					for (int i = 0; i < header.length; i++) {
						if (i == xColumn || i == yColumn || i == wktColumn) {
							continue;
						}
						attributes.put(header[i].strip(), inferCellValue(row[i]));
					}
					return new SourceFeature(geometry, attributes);
				}
			}

			@Override
			public boolean hasNext() {
				return pending != null;
			}

			@Override
			public SourceFeature next() {
				if (pending == null) {
					throw new NoSuchElementException();
				}
				SourceFeature result = pending;
				pending = advance();
				return result;
			}
		};
		return streamOf(iterator).onClose(() -> closeQuietly(csvReader));
	}

	@Override
	public void close() {
		// no persistent handle -- schema() and features() each open and close their own reader
	}

	private static void closeQuietly(CSVReader reader) {
		try {
			reader.close();
		} catch (IOException ignored) {
			// reading is already finished at this point; nothing left to do about it
		}
	}

	// --- geometry and value parsing ------------------------------------------

	private Geometry toGeometry(String[] row) {
		if (xColumn != -1 && yColumn != -1) {
			Double x = parseDecimal(row[xColumn], decimalComma);
			Double y = parseDecimal(row[yColumn], decimalComma);
			if (x == null || y == null) {
				return null;
			}
			return geometryFactory.createPoint(new Coordinate(x, y));
		}
		if (wktColumn != -1) {
			String wkt = row[wktColumn];
			if (wkt == null || wkt.isBlank()) {
				return null;
			}
			try {
				return wktReader.read(wkt.strip());
			} catch (ParseException e) {
				return null;
			}
		}
		return null;
	}

	private Object inferCellValue(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.strip();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
			return Boolean.valueOf(trimmed);
		}
		if (INTEGER.matcher(trimmed).matches()) {
			try {
				return Long.valueOf(trimmed);
			} catch (NumberFormatException e) {
				return trimmed; // overflows a long: keep as text rather than lose precision
			}
		}
		boolean matchesConfiguredDecimal = decimalComma
				? COMMA_DECIMAL.matcher(trimmed).matches()
				: DOT_DECIMAL.matcher(trimmed).matches();
		if (matchesConfiguredDecimal) {
			Double value = parseDecimal(trimmed, decimalComma);
			if (value != null) {
				return value;
			}
		}
		return trimmed;
	}

	private static Double parseDecimal(String raw, boolean decimalComma) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.strip();
		if (trimmed.isEmpty()) {
			return null;
		}
		String normalized = decimalComma
				? trimmed.replace(".", "").replace(",", ".")
				: trimmed.replace(",", "");
		try {
			return Double.valueOf(normalized);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// --- sniffing --------------------------------------------------------------

	private static boolean detectDecimalComma(List<String[]> rows) {
		int commaHits = 0;
		int dotHits = 0;
		for (String[] row : rows) {
			for (String cell : row) {
				if (cell == null) {
					continue;
				}
				String trimmed = cell.strip();
				if (COMMA_DECIMAL.matcher(trimmed).matches()) {
					commaHits++;
				} else if (DOT_DECIMAL.matcher(trimmed).matches()) {
					dotHits++;
				}
			}
		}
		return commaHits > dotHits;
	}

	private static char detectDelimiter(List<String> lines) {
		char best = ';';
		int bestScore = -1;
		for (char candidate : DELIMITER_CANDIDATES) {
			int score = scoreDelimiter(candidate, lines);
			if (score > bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}

	private static int scoreDelimiter(char delimiter, List<String> lines) {
		int firstCount = -1;
		int consistentLines = 0;
		for (String line : lines) {
			if (line.isBlank()) {
				continue;
			}
			int count = countOutsideQuotes(line, delimiter);
			if (count == 0) {
				continue;
			}
			if (firstCount == -1) {
				firstCount = count;
			}
			if (count == firstCount) {
				consistentLines++;
			}
		}
		return firstCount <= 0 ? 0 : consistentLines * 1000 + firstCount;
	}

	private static int countOutsideQuotes(String line, char delimiter) {
		int count = 0;
		boolean inQuotes = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '"') {
				inQuotes = !inQuotes;
			} else if (c == delimiter && !inQuotes) {
				count++;
			}
		}
		return count;
	}

	private static List<String> readRawLines(Path file, Charset charset, int maxLines) {
		try (BufferedReader reader = new BufferedReader(CharsetDetector.openTextReader(file, charset))) {
			List<String> lines = new ArrayList<>();
			String line;
			while (lines.size() < maxLines && (line = reader.readLine()) != null) {
				lines.add(line);
			}
			return lines;
		} catch (IOException e) {
			throw new SourceReadException("Der Import kann die CSV-Datei nicht lesen: " + file, e);
		}
	}

	private CSVReader openCsvReader() {
		CSVParser parser = new CSVParserBuilder().withSeparator(delimiter).build();
		Reader reader = CharsetDetector.openTextReader(file, charset);
		return new CSVReaderBuilder(reader).withCSVParser(parser).build();
	}
}
