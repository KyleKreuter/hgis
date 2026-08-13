package de.kreuter.hgis.wms;

/**
 * The WMS service named by the client's {@code url} could not be read: no answer within
 * the timeout, a non-2xx status, a response that is not readable XML, or one that exceeds
 * the size cap. One type for all four -- the client is told the same thing either way,
 * "the service did not answer", since none of them is this backend's fault and none is
 * a mistake in the request itself (that is {@link de.kreuter.hgis.common.BadRequestException},
 * for an address the guard refuses before any network call). Mapped to 502 by
 * {@link WmsOutageAdvice}, the same reasoning {@code GeoportalUnavailableException}
 * applies to Hamburg's own catalog services.
 */
class WmsUnavailableException extends RuntimeException {

	WmsUnavailableException(String message) {
		super(message);
	}
}
