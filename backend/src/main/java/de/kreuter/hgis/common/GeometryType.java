package de.kreuter.hgis.common;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Geometry type of a layer's payload table.
 *
 * Always a multi variant, or {@code GEOMETRY} for a genuinely mixed source -- single
 * geometries are promoted to their multi form on write (both on import and on a hand
 * drawn edit), so a stray point in a line file, or one polygon among many, never fails
 * the whole operation.
 *
 * Lives in {@code common} rather than {@code ingest}, even though it started out nested
 * in {@link de.kreuter.hgis.ingest.spi.SourceSchema}: the catalog now creates layers of
 * its own too, for a user to draw straight into, and {@code catalog} must not depend on
 * {@code ingest} to name their geometry type -- that dependency already runs the other
 * way round.
 */
public enum GeometryType {
	MULTIPOINT, MULTILINESTRING, MULTIPOLYGON, GEOMETRY;

	/**
	 * The three single-geometry tokens a caller most plausibly tries, and the multi
	 * variant this table would actually need for it -- deliberately not a silent mapping
	 * (Aufgabe 18, decided 26.08.): the column stays a multi-geometry, and a caller who
	 * sends a genuinely single-part source discovers that on the first multi-part object,
	 * not up front. Naming the partner in the rejection is the honest middle way.
	 */
	private static final Map<String, GeometryType> SINGLE_PARTNER = Map.of(
			"POINT", MULTIPOINT,
			"LINESTRING", MULTILINESTRING,
			"POLYGON", MULTIPOLYGON);

	/** The German noun {@link #SINGLE_PARTNER}'s hint names the rejected token by. */
	private static final Map<String, String> SINGLE_NOUN = Map.of(
			"POINT", "Punkte",
			"LINESTRING", "Linien",
			"POLYGON", "Flächen");

	/**
	 * The message for a token that is not one of {@link #values()} -- always names the
	 * valid values (the {@code LayerFields.require} pattern this follows: an unknown
	 * field name lists every field the layer actually has), and for {@code POINT},
	 * {@code LINESTRING} or {@code POLYGON} specifically also names the multi variant to
	 * use instead, since those three are not merely unknown but the one guess almost
	 * everyone makes first.
	 */
	public static String unknownTypeMessage(String raw) {
		String base = "Unbekannter Geometrietyp: " + raw + ". Gültig sind " + joinedValues();
		GeometryType partner = SINGLE_PARTNER.get(raw);
		if (partner == null) {
			return base + ".";
		}
		return base + " -- für " + SINGLE_NOUN.get(raw) + " nehmen Sie " + partner.name() + ".";
	}

	private static String joinedValues() {
		return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
	}
}
