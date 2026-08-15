package de.kreuter.hgis.places;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.places.dto.PlaceDtos;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * {@code GET /api/places}: Hamburg first, Photon after -- CONTRACT.md's ordering rule.
 *
 * <p>{@code limit} is spent on Hamburg first and Photon only fills what is left, rather
 * than asking both sources for the full amount and truncating the merged list. Two reasons:
 * CONTRACT.md's own worked example lists a Hamburg hit before a Photon hit for the very
 * same street name, which only happens if Hamburg is asked (and answers) first; and
 * CONTRACT.md separately asks to "sei sparsam mit Anfragen an photon.komoot.io" -- a local
 * query that already filled the page has no reason to spend one of Photon's donated
 * requests on results that would only be discarded.
 */
@Service
public class PlaceSearchService {

	static final int DEFAULT_LIMIT = 10;
	static final int MAX_LIMIT = 25;

	private final HamburgPlaceQuery hamburgQuery;
	private final PhotonClient photonClient;

	PlaceSearchService(HamburgPlaceQuery hamburgQuery, PhotonClient photonClient) {
		this.hamburgQuery = hamburgQuery;
		this.photonClient = photonClient;
	}

	public PlaceDtos.Response search(String q, Integer requestedLimit) {
		String term = q == null ? "" : q.trim();
		if (term.length() < 2) {
			throw new BadRequestException("Der Suchbegriff muss mindestens zwei Zeichen haben.");
		}
		int limit = clampLimit(requestedLimit);

		List<PlaceDtos.Result> hamburg = hamburgQuery.search(term, limit);

		int remaining = limit - hamburg.size();
		List<PlaceDtos.Result> photon = remaining > 0 ? photonClient.search(term, remaining) : List.of();

		List<PlaceDtos.Result> combined = new ArrayList<>(hamburg.size() + photon.size());
		combined.addAll(hamburg);
		combined.addAll(photon);
		return new PlaceDtos.Response(combined);
	}

	/** Vorgabe 10, Höchstwert 25 (CONTRACT.md). An absent or non-positive value falls back
	 *  to the default rather than being rejected -- the parameter is documented as
	 *  optional, so a client that sends {@code limit=0} by a bug gets a sane page instead
	 *  of a 400 for a field CONTRACT.md never asked it to validate. */
	private static int clampLimit(Integer requestedLimit) {
		if (requestedLimit == null || requestedLimit <= 0) {
			return DEFAULT_LIMIT;
		}
		return Math.min(requestedLimit, MAX_LIMIT);
	}
}
