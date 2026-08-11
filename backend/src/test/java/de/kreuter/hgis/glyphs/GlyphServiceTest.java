package de.kreuter.hgis.glyphs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import org.junit.jupiter.api.Test;

class GlyphServiceTest {

	private final GlyphService service = new GlyphService();

	@Test
	void loadsBundledLatinRange() {
		byte[] pbf = service.load(GlyphService.NOTO_SANS_REGULAR, "0-255");
		assertThat(pbf).isNotEmpty();
		assertThat(pbf.length).isGreaterThan(100);
	}

	@Test
	void loadsEuroCurrencyRange() {
		byte[] pbf = service.load(GlyphService.NOTO_SANS_REGULAR, "8192-8447");
		assertThat(pbf).isNotEmpty();
	}

	@Test
	void takesPrimaryFaceFromCommaSeparatedStack() {
		byte[] pbf = service.load(GlyphService.NOTO_SANS_REGULAR + ",Arial Unicode MS", "0-255");
		assertThat(pbf).isNotEmpty();
	}

	@Test
	void rejectsUnknownFont() {
		assertThatThrownBy(() -> service.load("Comic Sans", "0-255"))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	void rejectsPathTraversalInFontstack() {
		assertThatThrownBy(() -> service.load("../etc", "0-255"))
				.isInstanceOf(BadRequestException.class);
	}

	@Test
	void rejectsMalformedRange() {
		assertThatThrownBy(() -> service.load(GlyphService.NOTO_SANS_REGULAR, "abc"))
				.isInstanceOf(BadRequestException.class);
		assertThatThrownBy(() -> service.load(GlyphService.NOTO_SANS_REGULAR, "../../x"))
				.isInstanceOf(BadRequestException.class);
	}

	@Test
	void missingRangeIsNotFound() {
		assertThatThrownBy(() -> service.load(GlyphService.NOTO_SANS_REGULAR, "0-1"))
				.isInstanceOf(NotFoundException.class);
	}
}
