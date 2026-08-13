package de.kreuter.hgis.common;

/**
 * The request named a real resource, and the resource answered, but its content cannot
 * serve what it was asked for -- a WMS service speaking a protocol version this
 * application does not read, or one that never offers Web Mercator (EPSG:3857), the one
 * CRS the map renders in without reprojecting. Distinct from {@link BadRequestException}:
 * the request itself was fine, and the server had to fetch and read the resource before
 * it could tell. Mapped to 422 by {@link ProblemDetailAdvice}.
 */
public class UnprocessableEntityException extends RuntimeException {

	public UnprocessableEntityException(String message) {
		super(message);
	}
}
