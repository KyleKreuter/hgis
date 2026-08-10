package de.kreuter.hgis.ingest.reader.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Packs every file next to a Shapefile's .shp into a ZIP, the way an upload arrives. */
public final class TestZips {

	private TestZips() {
	}

	public static Path zipShapefileSet(Path shpFile, Path targetZip) throws IOException {
		Path dir = shpFile.getParent();
		String baseName = shpFile.getFileName().toString();
		String base = baseName.substring(0, baseName.length() - 4);

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(targetZip));
				Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(p -> p.getFileName().toString().startsWith(base)).toList()) {
				zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
				Files.copy(file, zip);
				zip.closeEntry();
			}
		}
		return targetZip;
	}
}
