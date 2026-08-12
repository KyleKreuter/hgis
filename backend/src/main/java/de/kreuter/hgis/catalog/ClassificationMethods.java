package de.kreuter.hgis.catalog;

import de.kreuter.hgis.common.BadRequestException;
import java.util.List;

/**
 * The three classification methods {@code /classify} understands, and the one place that
 * decides whether a name a client sent matches one of them.
 *
 * <p>Shared between {@link ClassificationService}, which computes class boundaries by one
 * of these methods, and {@link LayerStyleService}, which only records which method a stored
 * graduated renderer's classes were computed with -- it never recomputes a boundary. Neither
 * owns the list of valid names; both would otherwise have to agree on it by hand, and drift
 * the moment a method is renamed or a fourth one is added.
 */
final class ClassificationMethods {

	static final String QUANTILE = "quantile";
	static final String EQUAL_INTERVAL = "equalInterval";
	static final String NATURAL_BREAKS = "naturalBreaks";

	private static final List<String> KNOWN = List.of(QUANTILE, EQUAL_INTERVAL, NATURAL_BREAKS);

	private ClassificationMethods() {
	}

	/**
	 * The canonical spelling of {@code method}, matched case-insensitively.
	 *
	 * @throws BadRequestException if {@code method} is not one of the three known methods
	 */
	static String require(String method) {
		for (String known : KNOWN) {
			if (known.equalsIgnoreCase(method.trim())) {
				return known;
			}
		}
		throw new BadRequestException("Unbekannte Methode: " + method + ". Erlaubt sind "
				+ String.join(", ", KNOWN) + ".");
	}
}
