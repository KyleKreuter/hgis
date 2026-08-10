package de.kreuter.hgis.ingest.reader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Determines the text encoding of Shapefile DBF attribute values and of CSV files.
 *
 * For Shapefiles the order is fixed: an explicit {@code .cpg} file wins, then the DBF
 * header's language driver ID byte, and only if neither is present is the content itself
 * sniffed. The sniffing fallback assumes Windows-1252 rather than ISO-8859-1 -- the two
 * agree below 0xA0, but German shapefiles overwhelmingly come from Windows tools, which
 * use 1252's extra punctuation (curly quotes, en-dash) in that upper range.
 */
final class CharsetDetector {

	static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

	private static final int SNIFF_SAMPLE_LIMIT = 500;
	private static final int CSV_SNIFF_BYTES = 200_000;

	private CharsetDetector() {
	}

	static Charset detectForShapefile(Path shpFile, Charset override) {
		if (override != null) {
			return override;
		}

		Optional<Path> cpg = findSibling(shpFile, "cpg");
		if (cpg.isPresent()) {
			Charset fromCpg = readCpgFile(cpg.get());
			if (fromCpg != null) {
				return fromCpg;
			}
		}

		Optional<Path> dbf = findSibling(shpFile, "dbf");
		if (dbf.isPresent()) {
			Charset fromLdid = fromLdid(dbf.get());
			if (fromLdid != null) {
				return fromLdid;
			}
			return sniffDbf(dbf.get());
		}
		return StandardCharsets.UTF_8;
	}

	static Charset detectForCsv(Path csvFile, Charset override) {
		if (override != null) {
			return override;
		}
		byte[] prefix = readPrefix(csvFile, CSV_SNIFF_BYTES);
		if (startsWithUtf8Bom(prefix)) {
			return StandardCharsets.UTF_8;
		}
		return isValidUtf8(prefix) ? StandardCharsets.UTF_8 : WINDOWS_1252;
	}

	/** Opens a file for text reading, transparently skipping a UTF-8 byte order mark if present. */
	static Reader openTextReader(Path file, Charset charset) {
		try {
			InputStream in = Files.newInputStream(file);
			if (!charset.equals(StandardCharsets.UTF_8)) {
				return new InputStreamReader(in, charset);
			}
			PushbackInputStream pushback = new PushbackInputStream(in, 3);
			byte[] bom = new byte[3];
			int read = pushback.read(bom);
			if (read < 3 || !isUtf8Bom(bom)) {
				if (read > 0) {
					pushback.unread(bom, 0, read);
				}
			}
			return new InputStreamReader(pushback, charset);
		} catch (IOException e) {
			throw new SourceReadException("Datei konnte nicht geöffnet werden: " + file, e);
		}
	}

	// --- .cpg -------------------------------------------------------------

	private static Charset readCpgFile(Path cpgFile) {
		try {
			String raw = Files.readString(cpgFile, StandardCharsets.ISO_8859_1).strip();
			return parseCpgLabel(raw);
		} catch (IOException e) {
			throw new SourceReadException("CPG-Datei konnte nicht gelesen werden: " + cpgFile, e);
		}
	}

	static Charset parseCpgLabel(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String label = raw.strip();
		try {
			return Charset.forName(label);
		} catch (RuntimeException ignored) {
			// not a direct charset name -- fall through to codepage-number handling
		}
		String digits = label.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) {
			return null;
		}
		return charsetFromCodepageNumber(Integer.parseInt(digits));
	}

	private static Charset charsetFromCodepageNumber(int codepage) {
		return switch (codepage) {
			case 65001 -> StandardCharsets.UTF_8;
			case 1252 -> WINDOWS_1252;
			case 850 -> tryCharset("IBM850");
			case 437 -> tryCharset("IBM437");
			case 8859, 28591 -> StandardCharsets.ISO_8859_1;
			default -> tryCharset("cp" + codepage);
		};
	}

	private static Charset tryCharset(String name) {
		try {
			return Charset.forName(name);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// --- DBF language driver ID --------------------------------------------

	/**
	 * Byte 29 (0-indexed) of the DBF header. 0x00 means "unset" in practice -- many writers
	 * leave it at the default -- so it is treated the same as an unrecognised code: fall
	 * through to sniffing rather than trusting a value nobody actually chose.
	 */
	private static Charset fromLdid(Path dbfFile) {
		try (InputStream in = Files.newInputStream(dbfFile)) {
			byte[] header = in.readNBytes(32);
			if (header.length < 30) {
				return null;
			}
			int ldid = header[29] & 0xFF;
			return charsetForLdid(ldid);
		} catch (IOException e) {
			throw new SourceReadException("DBF-Header konnte nicht gelesen werden: " + dbfFile, e);
		}
	}

	private static Charset charsetForLdid(int ldid) {
		return switch (ldid) {
			case 0x01, 0x09, 0x0B, 0x0D, 0x11, 0x15, 0x19, 0x1B -> tryCharset("IBM437");
			case 0x02, 0x0A, 0x10, 0x13, 0x14, 0x16, 0x17, 0x18, 0x1A, 0x1D, 0x25, 0x37 -> tryCharset("IBM850");
			case 0x03, 0x57, 0x58, 0x59 -> WINDOWS_1252;
			case 0xC8 -> tryCharset("windows-1250");
			case 0xC9 -> tryCharset("windows-1251");
			case 0xCA -> tryCharset("windows-1254");
			case 0xCB -> tryCharset("windows-1253");
			case 0xCC -> tryCharset("windows-1257");
			default -> null;
		};
	}

	// --- content sniffing ---------------------------------------------------

	private static Charset sniffDbf(Path dbfFile) {
		List<byte[]> samples = collectDbfTextSamples(dbfFile, SNIFF_SAMPLE_LIMIT);
		for (byte[] sample : samples) {
			if (!isValidUtf8(sample)) {
				return WINDOWS_1252;
			}
		}
		return StandardCharsets.UTF_8;
	}

	/** Reads raw bytes of every character-field value, bypassing any charset assumption. */
	private static List<byte[]> collectDbfTextSamples(Path dbfFile, int limit) {
		List<byte[]> samples = new ArrayList<>();
		try (RandomAccessFile raf = new RandomAccessFile(dbfFile.toFile(), "r")) {
			byte[] header = new byte[32];
			raf.readFully(header);
			int headerSize = readLeUnsignedShort(header, 8);
			int recordSize = readLeUnsignedShort(header, 10);
			long recordCount = readLeUnsignedInt(header, 4);

			int fieldBytes = headerSize - 32 - 1;
			if (fieldBytes <= 0 || fieldBytes % 32 != 0 || recordSize <= 0) {
				return samples; // not a well-formed header; let the caller default sensibly
			}

			record CharField(int offsetInRecord, int length) {
			}
			List<CharField> charFields = new ArrayList<>();
			byte[] descriptor = new byte[32];
			int offsetInRecord = 1; // record starts with a deletion-flag byte
			for (int i = 0; i < fieldBytes / 32; i++) {
				raf.readFully(descriptor);
				char type = (char) descriptor[11];
				int length = descriptor[16] & 0xFF;
				if (type == 'C') {
					charFields.add(new CharField(offsetInRecord, length));
				}
				offsetInRecord += length;
			}
			if (charFields.isEmpty()) {
				return samples;
			}

			raf.seek(headerSize);
			byte[] record = new byte[recordSize];
			for (long r = 0; r < recordCount && samples.size() < limit; r++) {
				if (raf.read(record) < recordSize) {
					break;
				}
				for (CharField field : charFields) {
					int end = Math.min(field.offsetInRecord() + field.length(), recordSize);
					byte[] value = trimTrailingSpaces(record, field.offsetInRecord(), end);
					if (value.length > 0) {
						samples.add(value);
						if (samples.size() >= limit) {
							break;
						}
					}
				}
			}
		} catch (IOException e) {
			throw new SourceReadException("DBF-Datei konnte nicht gelesen werden: " + dbfFile, e);
		}
		return samples;
	}

	private static boolean isValidUtf8(byte[] sample) {
		var decoder = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			decoder.decode(ByteBuffer.wrap(sample));
			return true;
		} catch (CharacterCodingException e) {
			return false;
		}
	}

	// --- shared helpers -----------------------------------------------------

	private static Optional<Path> findSibling(Path anchor, String extension) {
		Path dir = anchor.toAbsolutePath().getParent();
		String base = stripExtension(anchor.getFileName().toString());
		Path direct = dir.resolve(base + "." + extension);
		if (Files.exists(direct)) {
			return Optional.of(direct);
		}
		String wanted = (base + "." + extension).toLowerCase(Locale.ROOT);
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
		} catch (IOException e) {
			throw new SourceReadException("Verzeichnis konnte nicht gelesen werden: " + dir, e);
		}
	}

	private static String stripExtension(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? fileName : fileName.substring(0, dot);
	}

	private static byte[] readPrefix(Path file, int maxBytes) {
		try (InputStream in = Files.newInputStream(file)) {
			return in.readNBytes(maxBytes);
		} catch (IOException e) {
			throw new SourceReadException("Datei konnte nicht gelesen werden: " + file, e);
		}
	}

	private static boolean startsWithUtf8Bom(byte[] bytes) {
		return bytes.length >= 3 && isUtf8Bom(bytes);
	}

	private static boolean isUtf8Bom(byte[] bytes) {
		return (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
	}

	private static byte[] trimTrailingSpaces(byte[] record, int start, int end) {
		int trimmedEnd = end;
		while (trimmedEnd > start && record[trimmedEnd - 1] == ' ') {
			trimmedEnd--;
		}
		byte[] result = new byte[trimmedEnd - start];
		System.arraycopy(record, start, result, 0, result.length);
		return result;
	}

	private static int readLeUnsignedShort(byte[] bytes, int offset) {
		return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
	}

	private static long readLeUnsignedInt(byte[] bytes, int offset) {
		return (bytes[offset] & 0xFFL)
				| ((bytes[offset + 1] & 0xFFL) << 8)
				| ((bytes[offset + 2] & 0xFFL) << 16)
				| ((bytes[offset + 3] & 0xFFL) << 24);
	}
}
