package de.kreuter.hgis.geoportal;

/**
 * Unchecked failure while fetching or parsing one of the two catalog files. {@link
 * GeoportalCatalogService} is the only caller of {@link CatalogLoader#load()} and decides
 * what this means for the held snapshot -- keep serving it (plan section 7.2) or propagate
 * the failure, depending on whether one exists yet.
 */
class CatalogLoadException extends RuntimeException {

	CatalogLoadException(String message) {
		super(message);
	}

	CatalogLoadException(String message, Throwable cause) {
		super(message, cause);
	}
}
