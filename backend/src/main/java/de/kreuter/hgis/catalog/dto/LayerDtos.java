package de.kreuter.hgis.catalog.dto;

// Jackson 3 moved core and databind to tools.jackson, but the annotations stayed on
// com.fasterxml.jackson.annotation -- they are still the 2.x artifact.
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Transport types for the layer API. Grouped in one file because they are small,
 * closely related and always read together -- mirrors {@code ProjectDtos}.
 */
public final class LayerDtos {

	private LayerDtos() {
	}

	/** Row in a project's layer list. */
	public record Summary(
			UUID id,
			String name,
			String geometryType,
			int srid,
			long featureCount,
			boolean visible,
			int zIndex,
			int minZoom,
			int maxZoom,
			long dataVersion,
			long styleVersion,

			/** [minLng, minLat, maxLng, maxLat] in EPSG:4326, or null if the layer is empty. */
			double[] extent,

			/**
			 * The layer's style, see {@link StyleDtos}, or absent for the default monochrome
			 * rendering.
			 *
			 * <p>Part of the summary and not only of the detail because the map synchronises
			 * itself against this list. Everything it needs to draw a layer has to be in the
			 * same response, or opening a project would cost one detail request per layer
			 * before the first feature can be coloured.
			 *
			 * <p>Held as the stored JSON and written straight through rather than parsed and
			 * re-serialised: it was canonicalised by {@code LayerStyleService} on the way in,
			 * so a round trip would only be an opportunity to drift.
			 */
			@JsonRawValue
			@JsonInclude(JsonInclude.Include.NON_NULL)
			String style,

			/**
			 * This layer's own basemap, or null to follow the project's. Present here for the
			 * same reason as {@link #style()}: the map has to know, for whichever layer becomes
			 * active, whether it overrides the project's basemap -- without a detail request
			 * per layer.
			 */
			String basemap,

			/** This layer's own opacity for the basemap, or null to follow the project's. */
			Double basemapOpacity,

			/**
			 * Null, or this layer's role as one of the project's clip masks: {@code
			 * "insideWhole"}, {@code "insideClipped"}, {@code "outsideWhole"} or {@code
			 * "outsideClipped"} (CONTRACT.md phase 21). Any number of layers per project
			 * may carry a non-null value at once. Non-null even while {@code visible} is
			 * false -- the clip a mask produces does not depend on whether the mask
			 * itself is drawn.
			 */
			String clipMode,

			/**
			 * Cache-buster for the combined effect of every clip mask on this layer's
			 * tiles, carried in the tile URL alongside {@link #dataVersion()} and
			 * {@link #styleVersion()}. Zero when no mask currently affects this layer:
			 * none is marked in the project, this layer is itself the only one, or none
			 * sits below its {@code zIndex}. Computed fresh on every read from the
			 * effective masks' current identity, {@code dataVersion} and {@code
			 * clipMode} rather than stored, so marking, unmarking, editing or reordering
			 * any mask takes effect immediately, with no invalidation step to get wrong.
			 * See {@link de.kreuter.hgis.catalog.Layer#clipVersion}.
			 */
			long clipVersion,

			/**
			 * The fourth and last part of the tile address: how this build renders a
			 * tile, identical for every layer of every project. The other three versions
			 * follow the data; this one follows the code, and it is what makes a change
			 * in rendering <em>meaning</em> reach clients that hold an immutable tile.
			 * See {@link de.kreuter.hgis.common.TileRenderVersion} for when it is raised.
			 */
			int renderVersion) {
	}

	/** One entry of {@code LayerDetail.fields}. */
	public record Field(UUID id, String sourceName, String columnName, String dataType) {
	}

	/**
	 * Full layer, returned for a single layer. Exactly {@link Summary} plus the attribute
	 * list and the timestamps -- the members are kept in that order so the relationship
	 * stays readable.
	 */
	public record Detail(
			UUID id,
			String name,
			String geometryType,
			int srid,
			long featureCount,
			boolean visible,
			int zIndex,
			int minZoom,
			int maxZoom,
			long dataVersion,
			long styleVersion,
			double[] extent,

			/** @see Summary#style() */
			@JsonRawValue
			@JsonInclude(JsonInclude.Include.NON_NULL)
			String style,

			/** @see Summary#basemap() */
			String basemap,

			/** @see Summary#basemapOpacity() */
			Double basemapOpacity,

			/** @see Summary#clipMode() */
			String clipMode,

			/** @see Summary#clipVersion() */
			long clipVersion,

			/** @see Summary#renderVersion() */
			int renderVersion,

			List<Field> fields,
			Instant createdAt,
			Instant updatedAt) {
	}

	/**
	 * Request to create a brand-new, empty layer -- ready to draw into immediately,
	 * rather than the by-product of a file import.
	 */
	public record CreateRequest(
			@NotBlank(message = "Name darf nicht leer sein")
			@Size(max = 200, message = "Name darf höchstens 200 Zeichen lang sein")
			String name,

			/**
			 * One of MULTIPOINT, MULTILINESTRING, MULTIPOLYGON or GEOMETRY -- the last for
			 * a layer meant to hold a genuine mix of points, lines and polygons from the
			 * start, the same as an import produces. Kept as a plain string rather than the
			 * enum itself -- an unknown token would otherwise fail while Jackson reads the
			 * body, before validation gets a chance to name the field for the client.
			 */
			@NotBlank(message = "Geometrietyp darf nicht leer sein")
			String geometryType,

			/**
			 * Attribute fields to create alongside the layer, in the given order. May be
			 * absent or empty -- a layer without any is valid and shows only {@code fid} in
			 * the attribute table.
			 */
			List<Field> fields) {

		/** Null-safe reading of {@link #fields()}: absent means none. */
		public List<Field> fields() {
			return fields == null ? List.of() : fields;
		}

		/**
		 * One attribute field to create.
		 *
		 * @param name the display name, i.e. {@code layer_field.source_name}; the SQL
		 *             column name is derived from it the same way an import derives one
		 * @param type one of {@link de.kreuter.hgis.common.FieldType}'s nine tokens
		 */
		public record Field(String name, String type) {
		}
	}

	/**
	 * Partial update. Every field is optional; null means "leave unchanged".
	 * srid and geometryType are deliberately absent -- they are immutable after creation.
	 */
	public record UpdateRequest(
			@Size(max = 200, message = "Name darf höchstens 200 Zeichen lang sein")
			String name,

			Boolean visible,

			Integer zIndex,

			@Min(value = 0, message = "minZoom muss zwischen 0 und 24 liegen")
			@Max(value = 24, message = "minZoom muss zwischen 0 und 24 liegen")
			Integer minZoom,

			@Min(value = 0, message = "maxZoom muss zwischen 0 und 24 liegen")
			@Max(value = 24, message = "maxZoom muss zwischen 0 und 24 liegen")
			Integer maxZoom,

			/**
			 * The new style, see {@link StyleDtos}.
			 *
			 * <p>A tree rather than the typed record because this is the one field where
			 * "absent" and "null" have to mean different things: absent leaves the style
			 * as it is, an explicit null resets the layer to the default rendering. A
			 * record member cannot tell the two apart -- both arrive as null.
			 */
			JsonNode style,

			/**
			 * The layer's own basemap, as a JSON string, or {@code null} to make it follow
			 * the project's basemap again. Absent leaves it unchanged. A tree for the same
			 * reason as {@link #style()}: absent and an explicit {@code null} both arrive as
			 * a plain {@code String} null, so only a type that tells a missing field apart
			 * from a present JSON {@code null} can carry "reset" as its own meaning.
			 *
			 * <p>Not checked against a catalogue -- the server does not know one, see
			 * CONTRACT.md phase 18 -- only its length.
			 */
			JsonNode basemap,

			/**
			 * The layer's own opacity for the basemap, as a JSON number between 0 and 1, or
			 * {@code null} to make it follow the project's again. Absent leaves it unchanged.
			 * @see #basemap()
			 */
			JsonNode basemapOpacity,

			/**
			 * Sets or clears this layer's role as one of the project's clip masks
			 * (CONTRACT.md phase 21): {@code "insideWhole"}, {@code "insideClipped"},
			 * {@code "outsideWhole"} or {@code "outsideClipped"} as a JSON string to mark
			 * it, or an explicit JSON {@code null} to clear it. Absent leaves it
			 * unchanged -- a tree for the same reason as {@link #basemap()}: absent and an
			 * explicit {@code null} both arrive as a plain {@code String} null, so only a
			 * type that tells a missing field apart from a present JSON {@code null} can
			 * carry "clear" as its own meaning. A string other than the four known modes
			 * is rejected with 400, and so is any mode on a layer that is neither
			 * MULTIPOLYGON nor GEOMETRY. Any number of layers in a project may be masks at
			 * once -- marking this one never touches another layer.
			 */
			JsonNode clipMode) {
	}

	/**
	 * Request to add one attribute field to an existing layer (CONTRACT.md phase 11).
	 * Unlike {@link CreateRequest.Field}, this is not part of a batch: the layer already
	 * has a physical table, and this widens it by exactly one column.
	 */
	public record AddFieldRequest(
			@NotBlank(message = "Name darf nicht leer sein")
			@Size(max = 200, message = "Name darf höchstens 200 Zeichen lang sein")
			String name,

			/**
			 * One of {@link de.kreuter.hgis.common.FieldType}'s nine tokens. Kept as a
			 * plain string for the same reason as {@link CreateRequest#geometryType()}: an
			 * unknown token is rejected with a field-level message instead of failing while
			 * Jackson reads the body.
			 */
			@NotBlank(message = "Typ darf nicht leer sein")
			String type) {
	}

	/**
	 * Request to rename an existing field's display name. {@code columnName} and
	 * {@code dataType} never change -- see CONTRACT.md phase 11, trap 3.
	 */
	public record RenameFieldRequest(
			@NotBlank(message = "Name darf nicht leer sein")
			@Size(max = 200, message = "Name darf höchstens 200 Zeichen lang sein")
			String name) {
	}

	/**
	 * Answer to {@code GET .../fields/{fieldId}/usage} -- what deleting this field would
	 * touch (CONTRACT.md phase 12), for the confirmation dialog to name a real
	 * consequence instead of asking an empty question.
	 *
	 * @param valueCount     objects with a non-null value in this column
	 * @param usedByRenderer the style classifies by this field
	 * @param usedByLabels   the (enabled) labels are drawn from this field
	 */
	public record FieldUsage(long valueCount, boolean usedByRenderer, boolean usedByLabels) {
	}

	/**
	 * New stacking order for a whole project.
	 *
	 * <p>The field name states the direction because getting it wrong is invisible until
	 * someone looks at the map: the first entry ends up at {@code zIndex} 0 and is drawn
	 * first, hence lowest. A layer tree shows the reverse of this list, since a tree
	 * reads top-down.
	 *
	 * <p>The list has to name every layer of the project. A partial list would leave the
	 * position of the others undefined, and demanding the full set is also what keeps a
	 * layer from another project out.
	 */
	public record ReorderRequest(
			@NotEmpty(message = "Die Liste muss mindestens einen Layer enthalten")
			List<UUID> layerIdsBottomToTop) {
	}
}
