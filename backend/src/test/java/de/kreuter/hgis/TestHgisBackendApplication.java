package de.kreuter.hgis;

import org.springframework.boot.SpringApplication;

public class TestHgisBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(HgisBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
