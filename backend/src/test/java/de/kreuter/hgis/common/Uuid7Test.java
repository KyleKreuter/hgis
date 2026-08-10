package de.kreuter.hgis.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class Uuid7Test {

	@RepeatedTest(50)
	void generatesVersion7Uuids() {
		UUID id = Uuid7.generate();

		assertThat(id.version()).isEqualTo(7);
		// Variant bits must be "10" -- java.util.UUID.variant() reports that as 2.
		assertThat(id.variant()).isEqualTo(2);
	}

	@Test
	void producesUniqueValues() {
		Set<UUID> generated = new HashSet<>();
		for (int i = 0; i < 10_000; i++) {
			generated.add(Uuid7.generate());
		}
		assertThat(generated).hasSize(10_000);
	}

	@Test
	void sortsChronologicallyAcrossMilliseconds() throws InterruptedException {
		UUID first = Uuid7.generate();
		Thread.sleep(5);
		UUID second = Uuid7.generate();

		assertThat(first.toString()).isLessThan(second.toString());
	}

	@Test
	void producesValidLayerTableNames() {
		// The whole point of generating the id manually: SqlIdentifier.tableName must
		// accept it just like it accepts an @UuidGenerator-assigned id.
		String tableName = SqlIdentifier.tableName(Uuid7.generate());
		assertThat(SqlIdentifier.isValidLayerTable(tableName)).isTrue();
	}
}
