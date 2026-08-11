package de.kreuter.hgis.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kreuter.hgis.common.BadRequestException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FidSelectionTest {

	@Test
	@DisplayName("an absent parameter is the whole layer, a blank one is an empty selection")
	void separatesAbsentFromEmpty() {
		// The whole point of the parameter: these two must never collapse into one.
		assertThat(FidSelection.parse(null).isWholeLayer()).isTrue();
		assertThat(FidSelection.parse(null).isEmptySelection()).isFalse();

		assertThat(FidSelection.parse("").isWholeLayer()).isFalse();
		assertThat(FidSelection.parse("").isEmptySelection()).isTrue();
		assertThat(FidSelection.parse("   ").isEmptySelection()).isTrue();
	}

	@Test
	void readsACommaSeparatedList() {
		assertThat(FidSelection.parse("3, 1 ,2").fids()).containsExactly(3L, 1L, 2L);
	}

	@Test
	void rejectsANonNumericEntry() {
		assertThatThrownBy(() -> FidSelection.parse("1,2,'; DROP TABLE x"))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("nur ganze Zahlen");
	}

	@Test
	void rejectsATrailingSeparator() {
		assertThatThrownBy(() -> FidSelection.parse("1,2,"))
				.isInstanceOf(BadRequestException.class);
	}

	@Test
	@DisplayName("an offending entry is echoed back only in part")
	void truncatesTheEchoedEntry() {
		assertThatThrownBy(() -> FidSelection.parse("x".repeat(5000)))
				.isInstanceOf(BadRequestException.class)
				.satisfies(ex -> assertThat(ex.getMessage()).hasSizeLessThan(120));
	}

	@Test
	void rejectsAnAbsurdlyLargeSelection() {
		List<Long> tooMany = new ArrayList<>(FidSelection.MAX_FIDS + 1);
		for (long fid = 0; fid <= FidSelection.MAX_FIDS; fid++) {
			tooMany.add(fid);
		}

		assertThatThrownBy(() -> new FidSelection(tooMany))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("höchstens");
	}

	@Test
	@DisplayName("a null inside the list is rejected rather than exported as a missing row")
	void rejectsANullEntry() {
		// Reachable from a JSON body: {"fids": [1, null]}.
		assertThatThrownBy(() -> new FidSelection(Arrays.asList(1L, null)))
				.isInstanceOf(BadRequestException.class);
	}

	@Test
	void doesNotShareTheCallersList() {
		List<Long> mutable = new ArrayList<>(List.of(1L, 2L));
		FidSelection selection = new FidSelection(mutable);
		mutable.add(3L);

		assertThat(selection.fids()).containsExactly(1L, 2L);
	}
}
