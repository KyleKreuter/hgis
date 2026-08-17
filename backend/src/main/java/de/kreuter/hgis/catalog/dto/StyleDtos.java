package de.kreuter.hgis.catalog.dto;

// Jackson 3 moved core and databind to tools.jackson, but the annotations stayed on
// com.fasterxml.jackson.annotation -- they are still the 2.x artifact.
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The semantic style schema stored in {@code layer.style}.
 *
 * <p>Deliberately not the MapLibre style specification. That spec is tied to one
 * renderer, and storing it would make a later export to QGIS or SLD impossible without
 * reverse engineering paint expressions. What is stored here says what the user meant --
 * "colour by this attribute, these values, these colours" -- and the frontend maps it
 * onto MapLibre expressions.
 *
 * <p>A missing style (null) is the default monochrome rendering, not an error. Nothing in
 * the pipeline ever has to write a style for a layer to work.
 *
 * <p>Every record is a partial shape: which members carry meaning depends on
 * {@code renderer.type} and {@code symbol.kind}. Modelling that as a sealed hierarchy
 * with polymorphic deserialization would be more precise on paper and considerably more
 * brittle in practice -- the discriminators are validated in
 * {@code LayerStyleService} instead, which also owns everything else the schema cannot
 * express (colour format, value ranges, and above all that a field name really is a field
 * of this layer).
 *
 * <p>Wrapper types throughout, never primitives: Jackson 3 turns FAIL_ON_NULL_FOR_PRIMITIVES
 * on by default, so a primitive would make an otherwise valid style unreadable as soon as
 * an optional member is simply absent.
 */
public final class StyleDtos {

	public static final String RENDERER_SINGLE = "single";
	public static final String RENDERER_CATEGORIZED = "categorized";
	public static final String RENDERER_GRADUATED = "graduated";
	public static final String RENDERER_HEATMAP = "heatmap";

	public static final String SYMBOL_MARKER = "marker";
	public static final String SYMBOL_LINE = "line";
	public static final String SYMBOL_FILL = "fill";

	private StyleDtos() {
	}

	/**
	 * @param version  schema version; only 1 exists so far
	 * @param opacity  0..1, applied to fill, line and marker alike
	 * @param minZoom  style-level zoom window, independent of the layer's own one
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Style(
			Integer version,
			Renderer renderer,
			Labels labels,
			Double opacity,
			Integer minZoom,
			Integer maxZoom) {
	}

	/**
	 * @param type       single, categorized, graduated or heatmap
	 * @param symbol     the one symbol, for single
	 * @param field      classification attribute, for categorized and graduated; the
	 *                   optional weighting attribute, for heatmap -- absent there, every
	 *                   point counts equally instead of by an attribute's value.
	 *                   <strong>Stored as the resolved {@code column_name}</strong>, see
	 *                   {@code LayerStyleService} -- that is the key the tile carries,
	 *                   so {@code ["get", field]} works without a second lookup.
	 * @param categories value to symbol, for categorized
	 * @param classes    numeric ranges to symbol, for graduated
	 * @param fallbackSymbol used for everything no category or class covers, null included
	 * @param method     graduated only: which of {@code /classify}'s methods computed
	 *                   {@code classes} -- quantile, equalInterval or naturalBreaks. Recorded
	 *                   so the panel can reopen the same classification without rebuilding it
	 *                   under a different one; the server never recomputes a boundary from it.
	 * @param classCount graduated only: how many classes {@code method} was asked to produce.
	 *                   Purely descriptive -- it may differ from {@code classes.size()} once
	 *                   boundaries collapse, see {@code ClassificationService#strictlyAscending}.
	 * @param ramp       graduated and heatmap: the colour ramp's display name -- one of a
	 *                   fixed catalogue ({@code LayerStyleService.COLOR_RAMPS}: {@code blues},
	 *                   {@code reds}, {@code greens}, {@code greys}, {@code diverging},
	 *                   {@code inferno}, {@code viridis}), kept in step with the frontend's
	 *                   own list. Unlike {@code palette}, an unknown name is not merely the
	 *                   client's concern to render or not: the frontend resolves it with a
	 *                   silent fallback to blue, so a typo would otherwise be stored, render
	 *                   wrong, and never say why -- see {@code LayerStyleService} for the
	 *                   review finding behind this.
	 * @param palette    categorized only: the colour palette's display name, checked only for
	 *                   length. Unlike {@code ramp}, the server keeps no palette catalogue --
	 *                   a categorized renderer's colours already live in
	 *                   {@code categories[].symbol}, so an unknown palette name has nothing
	 *                   left to resolve wrong.
	 * @param radius     heatmap only: influence radius in screen points, 1..100, default 30.
	 *                   Purely a client-side rendering parameter -- the server only checks
	 *                   the range, it never uses it to compute anything itself.
	 * @param intensity  heatmap only: a multiplier on the computed density, 0.1..5.0,
	 *                   default 1.0. Same role as {@code radius}: range-checked, otherwise
	 *                   the client's own concern.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Renderer(
			String type,
			Symbol symbol,
			String field,
			List<Category> categories,
			List<ClassBreak> classes,
			Symbol fallbackSymbol,
			String method,
			Integer classCount,
			String ramp,
			String palette,
			Double radius,
			Double intensity) {
	}

	/**
	 * @param value the attribute value this entry matches; a scalar, or null for the
	 *              features that have no value at all
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Category(

			/**
			 * Written out even when null, against the rule the rest of this file follows.
			 * Null is a value here, not the absence of one: "objects without a use type"
			 * is a category a user can legitimately colour, and {@code /values} offers it
			 * as one. Dropped from the document it would be indistinguishable from a
			 * half-filled entry whose value was never chosen, and a renderer reading the
			 * style back could only guess which of the two it has.
			 */
			@JsonInclude(JsonInclude.Include.ALWAYS)
			Object value,

			String label,
			Symbol symbol) {
	}

	/** Half-open in intent: {@code min} inclusive, {@code max} exclusive except for the last class. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ClassBreak(Double min, Double max, String label, Symbol symbol) {
	}

	/**
	 * One symbol for any geometry type, with {@code kind} saying which members apply:
	 * marker uses shape/size/fillColor/strokeColor/strokeWidth, line uses
	 * color/width/dashArray, fill uses fillColor/fillOpacity/outlineColor/outlineWidth.
	 *
	 * @param shape only {@code circle} renders as such -- MapLibre cannot draw squares or
	 *              triangles without sprite images. The member exists so the schema stays
	 *              stable once sprites arrive; other values are accepted and drawn as circles.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Symbol(
			String kind,
			String shape,
			Double size,
			String fillColor,
			String strokeColor,
			Double strokeWidth,
			String color,
			Double width,
			List<Double> dashArray,
			Double fillOpacity,
			String outlineColor,
			Double outlineWidth) {
	}

	/**
	 * @param field the attribute to write next to the geometry, stored as the resolved
	 *              {@code column_name} like {@link Renderer#field()}
	 * @param minZoom labels usually only make sense once the map is close enough
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Labels(
			Boolean enabled,
			String field,
			Double size,
			String color,
			String haloColor,
			Double haloWidth,
			Integer minZoom,
			Boolean allowOverlap) {

		/** Absent means off, so a labels block without the flag never pulls an attribute into the tile. */
		public boolean isEnabled() {
			return Boolean.TRUE.equals(enabled);
		}
	}
}
