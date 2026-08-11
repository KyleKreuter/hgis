package de.kreuter.hgis.catalog.dto;

// Jackson 3 moved core and databind to tools.jackson, but the annotations stayed on
// com.fasterxml.jackson.annotation -- they are still the 2.x artifact.
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
			String style) {
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

			List<Field> fields,
			Instant createdAt,
			Instant updatedAt) {
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
			JsonNode style) {
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
			@NotEmpty(message = "Es muss mindestens ein Layer angegeben werden")
			List<UUID> layerIdsBottomToTop) {
	}
}
