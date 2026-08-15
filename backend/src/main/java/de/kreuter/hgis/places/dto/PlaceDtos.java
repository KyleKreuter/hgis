package de.kreuter.hgis.places.dto;

import java.util.List;

/** Transport types for the place search API. Grouped the same way as JobDtos: small and only
 *  ever read together. */
public final class PlaceDtos {

	private PlaceDtos() {
	}

	/** Response for GET /api/places, matches the shape in CONTRACT.md. */
	public record Response(List<Result> places) {
	}

	/**
	 * One search hit, Hamburg or Photon alike -- CONTRACT.md deliberately gives both
	 * sources the same shape so the frontend never has to branch on where a hit came from.
	 *
	 * @param name    the name alone, no disambiguating suffix -- except for an
	 *                {@code "address"}, where street and house number together are the name
	 *                ({@code "Eickhoffweg 12"}), so that searching for the address as it is
	 *                written finds it
	 * @param context what distinguishes it from a same-named hit elsewhere, or null when
	 *                nothing is known to distinguish it by
	 * @param lng     EPSG:4326, always -- regardless of source
	 * @param lat     EPSG:4326, always -- regardless of source
	 * @param source  {@code "hamburg"} or {@code "photon"}
	 * @param kind    {@code "street"}, {@code "district"} (Hamburg only), {@code "place"} or
	 *                {@code "address"}
	 */
	public record Result(String name, String context, double lng, double lat, String source, String kind) {
	}
}
