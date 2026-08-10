package de.kreuter.hgis.ingest;

/**
 * A business-rule failure of the import itself, as opposed to an unexpected error --
 * for example, too large a share of skipped features. Compensated exactly like any
 * other failure: the partially written table is dropped and the job marked FAILED.
 */
class ImportFailedException extends RuntimeException {

	ImportFailedException(String message) {
		super(message);
	}
}
