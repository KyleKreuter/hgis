package de.kreuter.hgis.features.dto;

// Jackson 3 moved core and databind to tools.jackson, but the annotations stayed on
// com.fasterxml.jackson.annotation -- they are still the 2.x artifact.
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.util.List;
import java.util.Map;

/** Transport types for the feature API. */
public final class FeatureDtos {

	private FeatureDtos() {
	}

	/**
	 * One row of a layer.
	 *
	 * <p>{@code properties} is keyed by {@code column_name}, not by the source name the
	 * UI displays. Source names are not unique -- DBF truncates field names to ten
	 * characters, so two different attributes can arrive under one name -- and a map
	 * would silently lose one of them. The client maps back through {@code layer.fields}.
	 */
	public record Feature(
			long fid,

			/**
			 * PostgreSQL's {@code xmin} for this row. Read from the MVP onwards so
			 * optimistic locking (plan section D.7) needs no schema change later.
			 */
			String rowVersion,

			Map<String, Object> properties,

			/** GeoJSON in EPSG:4326, only when the request asked for it. */
			@JsonRawValue
			@JsonInclude(JsonInclude.Include.NON_NULL)
			String geometry) {
	}

	/**
	 * A page of features.
	 *
	 * @param nextCursor pass back as {@code cursor} for the next page; null at the end
	 * @param totalCount only filled for the first page of a query -- counting is a scan
	 *                   and the number cannot change while paging through a fixed filter
	 */
	public record Page(
			List<Feature> features,

			@JsonInclude(JsonInclude.Include.NON_NULL)
			String nextCursor,

			@JsonInclude(JsonInclude.Include.NON_NULL)
			Long totalCount) {
	}
}
