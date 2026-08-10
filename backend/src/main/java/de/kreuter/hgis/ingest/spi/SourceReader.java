package de.kreuter.hgis.ingest.spi;

import java.util.stream.Stream;

/**
 * Reads one source file. Implementations exist per format (Shapefile, GeoPackage,
 * GeoJSON, CSV) and are the only place that knows GeoTools.
 *
 * Usage is strictly two-phase: inspect the schema, decide whether to proceed (create
 * the table, ask the user about a guessed CRS), then stream the features. The split
 * exists because the table has to be created before the first insert, and because a
 * wrong CRS must be caught before any data is written.
 *
 * Implementations must be usable inside try-with-resources; the returned stream holds
 * file handles and must be closed by the caller.
 */
public interface SourceReader extends AutoCloseable {

	/**
	 * Inspects the source without consuming it. May sample the first features to
	 * determine geometry type or column types when the format does not declare them.
	 */
	SourceSchema schema();

	/**
	 * Streams all features lazily. Large files must not be materialised in memory, so
	 * implementations stream rather than build a list.
	 *
	 * Features whose geometry is null or unreadable are skipped by the reader and
	 * counted in {@link #skippedCount()} -- one broken record must not abort an import.
	 */
	Stream<SourceFeature> features();

	/** Number of records the reader had to skip, available after the stream is consumed. */
	long skippedCount();

	@Override
	void close();
}
