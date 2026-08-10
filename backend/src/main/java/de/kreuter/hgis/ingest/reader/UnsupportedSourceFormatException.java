package de.kreuter.hgis.ingest.reader;

/**
 * Thrown by {@link SourceReaderFactory} when a file's name does not match any format this
 * package knows how to read.
 */
public class UnsupportedSourceFormatException extends SourceReadException {

	public UnsupportedSourceFormatException(String message) {
		super(message);
	}
}
