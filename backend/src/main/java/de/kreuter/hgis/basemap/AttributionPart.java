package de.kreuter.hgis.basemap;

// Jackson 3 moved core and databind to tools.jackson, but the annotations stayed on
// com.fasterxml.jackson.annotation -- they are still the 2.x artifact.
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One run of attribution text, optionally a link -- the wire twin of {@code
 * AttributionPart} in {@code frontend/src/map/basemap.ts}, moved here so the backend can
 * hand the frontend pre-built runs instead of the frontend hardcoding them a second time.
 *
 * <p>The notice is broken into parts rather than kept as one string for the same reason
 * the frontend interface already gives: most licences ask for a *link* to the project
 * and to the licence, not just their names in prose -- "© OpenStreetMap contributors"
 * with nothing to click credits nobody who could be followed up.
 *
 * @param text always shown
 * @param href absolute https URL, or null for a run that is only text -- omitted from
 *     the JSON entirely rather than sent as {@code null}, matching the frontend's
 *     {@code href?: string}
 */
public record AttributionPart(String text, @JsonInclude(JsonInclude.Include.NON_NULL) String href) {

	/** A text-only run, without a link. */
	public AttributionPart(String text) {
		this(text, null);
	}
}
