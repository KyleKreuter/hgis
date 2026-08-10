package de.kreuter.hgis.ingest.reader;

/**
 * Unchecked wrapper for I/O and format problems encountered while reading a source file.
 *
 * {@link de.kreuter.hgis.ingest.spi.SourceReader} declares no checked exceptions -- a
 * reader either produces a schema and a stream of features, or it fails outright. Every
 * checked exception a format library throws (GeoTools, opencsv, JDBC, plain I/O) is
 * caught close to its source and rethrown as this type.
 */
public class SourceReadException extends RuntimeException {

	public SourceReadException(String message) {
		super(message);
	}

	public SourceReadException(String message, Throwable cause) {
		super(message, cause);
	}
}
