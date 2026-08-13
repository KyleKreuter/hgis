package de.kreuter.hgis.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule two contract clauses share (11.4 for field names, 11.9 for collection names),
 * on its own. What each caller does with a repeat is its own business and tested with it;
 * what has to be identical between them is which occurrences count as repeats.
 */
class AmbiguousTitlesTest {

	@Test
	@DisplayName("der erste Name bleibt, der zweite und jeder weitere gilt als Wiederholung")
	void theFirstOccurrenceIsNeverARepeat() {
		boolean[] repeats = AmbiguousTitles.repeats(List.of("Verbindungsräume", "Kernflächen", "Verbindungsräume",
				"Verbindungsräume"));

		assertThat(repeats).containsExactly(false, false, true, true);
	}

	@Test
	@DisplayName("ein einmaliger Name ist nie eine Wiederholung")
	void aUniqueTitleIsNeverARepeat() {
		assertThat(AmbiguousTitles.repeats(List.of("2013", "2014", "2015"))).containsExactly(false, false, false);
	}

	/**
	 * Two rows whose names differ only in case read as the same name in a list, and no
	 * reader could tell them apart -- which is the whole reason a name gets qualified.
	 */
	@Test
	@DisplayName("Groß- und Kleinschreibung unterscheidet zwei Namen nicht")
	void caseAloneDoesNotMakeTwoTitlesDifferent() {
		assertThat(AmbiguousTitles.repeats(List.of("Museen", "MUSEEN"))).containsExactly(false, true);
	}

	@Test
	@DisplayName("eine leere Liste ergibt keine Wiederholungen")
	void anEmptyListHasNoRepeats() {
		assertThat(AmbiguousTitles.repeats(List.of())).isEmpty();
	}
}
