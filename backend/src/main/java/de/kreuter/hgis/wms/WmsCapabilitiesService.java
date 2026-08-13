package de.kreuter.hgis.wms;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.wms.dto.WmsDtos;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * CONTRACT.md 2: reads a WMS service's own GetCapabilities document and turns it into
 * the flat, pre-validated shape the rest of the application works with. Also the
 * revalidation step stage 3 ({@link MapLayerService}) runs against the same service a
 * second time when a map image is actually created, so a service that changed its
 * layers between the two calls is caught rather than trusted from a stale answer.
 */
@Service
public class WmsCapabilitiesService {

	private static final Pattern SERVICE_OR_REQUEST_PARAM =
			Pattern.compile("[?&](?i:SERVICE|REQUEST)=", Pattern.CASE_INSENSITIVE);

	private final WmsCapabilitiesFetcher fetcher;

	WmsCapabilitiesService(WmsCapabilitiesFetcher fetcher) {
		this.fetcher = fetcher;
	}

	/**
	 * @param rawUrl the address as the client sent it -- with or without
	 *               {@code ?SERVICE=WMS&REQUEST=GetCapabilities} (CONTRACT.md 2)
	 */
	public WmsDtos.CapabilitiesResponse capabilities(String rawUrl) {
		String serviceUrl = baseUrl(rawUrl);
		URI requestUri = capabilitiesUri(rawUrl);
		byte[] xml = fetcher.fetch(requestUri);
		return WmsCapabilitiesParser.parse(xml, serviceUrl);
	}

	/** The address without any query string -- what CONTRACT.md's {@code serviceUrl} is. */
	private static String baseUrl(String rawUrl) {
		int queryStart = rawUrl.indexOf('?');
		return queryStart < 0 ? rawUrl : rawUrl.substring(0, queryStart);
	}

	/**
	 * Adds {@code SERVICE=WMS} and/or {@code REQUEST=GetCapabilities} exactly when the
	 * given address does not already carry one -- case-insensitively, since WMS
	 * parameter names are (OGC 06-042, section 6.3.2). Everything else the caller sent
	 * survives untouched, an authentication token in the query string included.
	 */
	private static URI capabilitiesUri(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			throw new BadRequestException("Diese Adresse ist nicht erlaubt.");
		}
		Set<String> present = new HashSet<>();
		Matcher paramMatcher = SERVICE_OR_REQUEST_PARAM.matcher(rawUrl);
		while (paramMatcher.find()) {
			String hit = paramMatcher.group();
			// hit is "?SERVICE=" / "&request=" / ... -- strip the leading separator and
			// the trailing '=' to get just the parameter name, upper-cased for the set.
			present.add(hit.substring(1, hit.length() - 1).toUpperCase(Locale.ROOT));
		}

		StringBuilder toAdd = new StringBuilder();
		if (!present.contains("SERVICE")) {
			toAdd.append(toAdd.isEmpty() ? "" : "&").append("SERVICE=WMS");
		}
		if (!present.contains("REQUEST")) {
			toAdd.append(toAdd.isEmpty() ? "" : "&").append("REQUEST=GetCapabilities");
		}

		String withParams = toAdd.isEmpty() ? rawUrl
				: rawUrl + (rawUrl.contains("?") ? "&" : "?") + toAdd;
		try {
			return new URI(withParams);
		}
		catch (URISyntaxException e) {
			throw new BadRequestException("Diese Adresse ist nicht erlaubt.");
		}
	}
}
