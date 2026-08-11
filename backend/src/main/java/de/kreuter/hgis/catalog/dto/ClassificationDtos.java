package de.kreuter.hgis.catalog.dto;

import java.util.List;

/**
 * Transport types for the two endpoints the symbology UI needs before it can propose a
 * style: class boundaries for a graduated renderer, and the values a categorized one
 * would have to cover.
 */
public final class ClassificationDtos {

	private ClassificationDtos() {
	}

	/**
	 * @param field     the resolved column name, which is also what belongs into
	 *                  {@code renderer.field} of the style
	 * @param breaks    strictly ascending boundaries, lower bounds plus the maximum. Normally
	 *                  {@code classes + 1} values; fewer when the data has fewer distinct
	 *                  values than requested classes, because a repeated boundary would
	 *                  describe an empty class
	 * @param min       null when the column holds no value at all
	 * @param nullCount features without a value; they fall to the fallback symbol
	 */
	public record Breaks(
			String field,
			String method,
			List<Double> breaks,
			Double min,
			Double max,
			long nullCount) {
	}

	/**
	 * @param values    most frequent first; null is a value like any other and appears with
	 *                  its own count
	 * @param truncated more distinct values exist than were asked for. Usually the answer
	 *                  to "should this be categorized at all?" rather than a paging hint
	 */
	public record Values(
			String field,
			List<ValueCount> values,
			boolean truncated) {
	}

	public record ValueCount(Object value, long count) {
	}
}
