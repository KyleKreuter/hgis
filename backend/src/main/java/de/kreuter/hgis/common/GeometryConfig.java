package de.kreuter.hgis.common;

import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GeometryConfig {

	/** WGS84 constant used for all metadata geometries (project extent and centre). */
	public static final int WGS84 = 4326;

	/**
	 * Factory for metadata geometries. Floating precision, because these values are
	 * map viewport state -- rounding them would slowly drift the saved position.
	 */
	@Bean
	public GeometryFactory wgs84GeometryFactory() {
		return new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING), WGS84);
	}
}
