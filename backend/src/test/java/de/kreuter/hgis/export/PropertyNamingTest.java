package de.kreuter.hgis.export;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.catalog.LayerField;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PropertyNamingTest {

	@Test
	@DisplayName("attributes are exported under the names the UI shows")
	void usesSourceNames() {
		List<ExportField> resolved = PropertyNaming.resolve(List.of(
				field("Straße", "strasse", 0),
				field("Höhe ü. NN", "hoehe_ue_nn", 1)));

		assertThat(resolved).extracting(ExportField::propertyKey)
				.containsExactly("Straße", "Höhe ü. NN");
		assertThat(resolved).extracting(ExportField::columnName)
				.containsExactly("strasse", "hoehe_ue_nn");
	}

	@Test
	@DisplayName("an attribute called fid yields to the row id and falls back to its column")
	void doesNotCollideWithTheRowId() {
		List<ExportField> resolved = PropertyNaming.resolve(List.of(field("fid", "fid_1", 0)));

		assertThat(resolved).extracting(ExportField::propertyKey).containsExactly("fid_1");
	}

	@Test
	@DisplayName("two attributes sharing a source name stay two attributes")
	void keepsDuplicateSourceNamesApart() {
		// What a DBF does: field names truncated to ten characters, so two different
		// attributes arrive under one name. Losing one of them silently is not an option.
		List<ExportField> resolved = PropertyNaming.resolve(List.of(
				field("EINWOHNER_", "einwohner_", 0),
				field("EINWOHNER_", "einwohner_1", 1)));

		assertThat(resolved).extracting(ExportField::propertyKey)
				.containsExactly("EINWOHNER_", "einwohner_1");
	}

	@Test
	@DisplayName("a fallback that is itself taken gets a suffix rather than a duplicate key")
	void resolvesACollisionBetweenSourceAndColumnNames() {
		// The second field would fall back to its column name "hoehe", which the first
		// field already occupies as its source name.
		List<ExportField> resolved = PropertyNaming.resolve(List.of(
				field("hoehe", "hoehe_1", 0),
				field("hoehe", "hoehe", 1)));

		assertThat(resolved).extracting(ExportField::propertyKey)
				.containsExactly("hoehe", "hoehe_2");
	}

	@Test
	void fallsBackToTheColumnForABlankSourceName() {
		assertThat(PropertyNaming.resolve(List.of(field("  ", "col", 0))))
				.extracting(ExportField::propertyKey)
				.containsExactly("col");
	}

	private static LayerField field(String sourceName, String columnName, int ordinal) {
		return new LayerField(null, sourceName, columnName, "text", ordinal);
	}
}
