package de.kreuter.hgis.common;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The basemaps the client actually knows how to draw -- mirrors {@code BASEMAPS} /
 * {@code BasemapId} in {@code frontend/src/map/basemap.ts}.
 *
 * <p>A project or layer stores the wire token as plain text (the {@code basemap} column
 * of both {@code project} and {@code layer}), the same as before this enum existed;
 * nothing about the storage shape changes. What this adds is a place to check a
 * client-supplied
 * token against the fixed list the frontend can actually render before it is written --
 * previously (Befund 1) neither {@code ProjectService} nor {@code LayerService} checked
 * that at all, so a typo such as {@code "grayscale"} was accepted with 200 and sat in the
 * database forever, silently falling back to OSM on every client that ever read it.
 *
 * <p>The wire token is kebab-case ({@code "osm-light"}), which a Java enum constant name
 * cannot be, so lookup goes through {@link #fromToken} and a small map rather than
 * {@link Enum#valueOf} -- unlike {@link GeometryType} and {@link FieldType}, whose tokens
 * already are valid enum names.
 */
public enum Basemap {

	OSM("osm"),
	OSM_LIGHT("osm-light"),
	OSM_DARK("osm-dark"),
	OPENTOPO("opentopo"),
	NONE("none");

	private final String token;

	Basemap(String token) {
		this.token = token;
	}

	/** The wire token this constant stands for, exactly as the frontend's {@code BasemapId} spells it. */
	public String token() {
		return token;
	}

	private static final Map<String, Basemap> BY_TOKEN = Arrays.stream(values())
			.collect(Collectors.toMap(Basemap::token, basemap -> basemap));

	/**
	 * @throws IllegalArgumentException with {@link #unknownTokenMessage(String)} when
	 *     {@code raw} is not one of {@link #values()}'s tokens
	 */
	public static Basemap fromToken(String raw) {
		Basemap basemap = BY_TOKEN.get(raw);
		if (basemap == null) {
			throw new IllegalArgumentException(unknownTokenMessage(raw));
		}
		return basemap;
	}

	/**
	 * The message for a token that is not one of {@link #values()}'s tokens -- always
	 * names the valid values, the same {@code *.unknownTypeMessage(raw)} pattern
	 * {@link GeometryType} and {@link FieldType} already follow (Aufgabe 18).
	 */
	public static String unknownTokenMessage(String raw) {
		return "Unbekannte Hintergrundkarte: " + raw + ". Gültig sind " + joinedTokens() + ".";
	}

	private static String joinedTokens() {
		return Arrays.stream(values()).map(Basemap::token).collect(Collectors.joining(", "));
	}
}
