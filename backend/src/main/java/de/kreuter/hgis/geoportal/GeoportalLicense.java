package de.kreuter.hgis.geoportal;

/**
 * The licence every service checked in the plan (section 4.1) names in its {@code Fees}
 * field: Datenlizenz Deutschland -- Namensnennung -- Version 2.0. Unlike a dataset's
 * attribution, which names the responsible agency and therefore differs per dataset (plan
 * section 4.2), the licence text and its link are the same for the whole catalog, which is
 * why CONTRACT.md 11.4 calls both of these "always set" rather than "null when the upstream
 * catalog carries none": there is nothing upstream to carry, they are fixed.
 */
final class GeoportalLicense {

	static final String NAME = "Datenlizenz Deutschland – Namensnennung – Version 2.0";
	static final String URL = "https://www.govdata.de/dl-de/by-2-0";

	private GeoportalLicense() {
	}
}
