package de.kreuter.hgis;

import org.geotools.util.factory.Hints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HgisBackendApplication {

	static {
		// EPSG:4326 is officially latitude/longitude, but virtually every tool and file
		// format treats it as longitude/latitude. Without forcing the axis order, imported
		// data silently lands on the wrong side of the planet -- no exception, no warning,
		// just wrong coordinates.
		//
		// This runs in a static initializer so it takes effect before Spring instantiates
		// any bean that might touch the GeoTools referencing subsystem. Once a CRS has been
		// decoded with the default axis order, the setting no longer helps.
		System.setProperty("org.geotools.referencing.forceXY", "true");
		Hints.putSystemDefault(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.TRUE);
	}

	public static void main(String[] args) {
		SpringApplication.run(HgisBackendApplication.class, args);
	}

}
