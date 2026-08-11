package de.kreuter.hgis.export;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * The size limit in front of the export parser, examined without a container.
 *
 * <p>The interesting case cannot be reached through MockMvc: a chunked request announces
 * no length, so there is nothing to check before reading and the limit has to hold while
 * the body arrives. Here that request can simply be built -- a {@code Content-Length} of
 * -1 and a body behind it -- which is what a streaming upload looks like from the filter's
 * side of the servlet API.
 */
class ExportBodyLimitFilterTest {

	private static final String PATH =
			"/api/layers/" + UUID.randomUUID() + "/export.geojson";

	private final ExportBodyLimitFilter filter = new ExportBodyLimitFilter(new ObjectMapper());

	private final MockHttpServletResponse response = new MockHttpServletResponse();
	private final MockFilterChain chain = new MockFilterChain();

	@Test
	@DisplayName("an ordinary body passes through and stays readable")
	void passesASmallBodyOn() throws Exception {
		String body = "{\"fids\":[1,2,3]}";
		MockHttpServletRequest request = post(body.getBytes(StandardCharsets.UTF_8));

		filter.doFilter(request, response, chain);

		HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
		assertThat(forwarded).isNotNull();
		assertThat(new String(forwarded.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
				.as("the body was read to measure it and must still be there afterwards")
				.isEqualTo(body);
		assertThat(forwarded.getContentLengthLong()).isEqualTo(body.length());
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("a declared length over the limit is refused without reading the body")
	void refusesAnOversizedContentLength() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH) {
			@Override
			public long getContentLengthLong() {
				return ExportBodyLimitFilter.MAX_BODY_BYTES + 1L;
			}

			@Override
			public ServletInputStream getInputStream() {
				throw new AssertionError("the body must not be touched once the size is known");
			}
		};

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(response.getContentType()).startsWith("application/problem+json");
		assertThat(chain.getRequest()).as("the chain was never entered").isNull();
	}

	@Test
	@DisplayName("a chunked body over the limit is refused while it arrives")
	void refusesAnOversizedChunkedBody() throws Exception {
		// No length to check first: this is the case the loop exists for.
		MockHttpServletRequest request = chunked(ExportBodyLimitFilter.MAX_BODY_BYTES + 1);

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("a chunked body inside the limit is accepted and gains a length")
	void acceptsAChunkedBodyWithinTheLimit() throws Exception {
		MockHttpServletRequest request = chunked(1024);

		filter.doFilter(request, response, chain);

		HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
		assertThat(forwarded).isNotNull();
		assertThat(forwarded.getInputStream().readAllBytes()).hasSize(1024);
		assertThat(forwarded.getContentLengthLong())
				.as("what arrived is now known, which it was not before")
				.isEqualTo(1024);
	}

	@Test
	@DisplayName("nothing else is buffered: not GET, not another path")
	void leavesEveryOtherRequestAlone() throws Exception {
		MockHttpServletRequest read = new MockHttpServletRequest("GET", PATH);
		filter.doFilter(read, response, chain);
		assertThat(chain.getRequest())
				.as("a GET export carries its selection in the query string")
				.isSameAs(read);

		MockFilterChain uploadChain = new MockFilterChain();
		MockHttpServletRequest upload =
				new MockHttpServletRequest("POST", "/api/projects/x/imports");
		upload.setContent(new byte[ExportBodyLimitFilter.MAX_BODY_BYTES + 1]);
		filter.doFilter(upload, new MockHttpServletResponse(), uploadChain);
		assertThat(uploadChain.getRequest())
				.as("an import is measured in hundreds of megabytes and has its own limit")
				.isSameAs(upload);
	}

	// --- helpers ---------------------------------------------------------------

	private static MockHttpServletRequest post(byte[] body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
		request.setContentType("application/json");
		request.setContent(body);
		return request;
	}

	/** A request that announces no size, the way {@code Transfer-Encoding: chunked} does. */
	private static MockHttpServletRequest chunked(int actualBytes) {
		byte[] body = new byte[actualBytes];
		return new MockHttpServletRequest("POST", PATH) {

			@Override
			public int getContentLength() {
				return -1;
			}

			@Override
			public long getContentLengthLong() {
				return -1;
			}

			@Override
			public ServletInputStream getInputStream() {
				return new ChunkedStream(body);
			}
		};
	}

	/** Hands the body out in small pieces, so a single read never sees all of it. */
	private static final class ChunkedStream extends ServletInputStream {

		private static final int CHUNK = 1500;

		private final byte[] body;
		private int position;

		private ChunkedStream(byte[] body) {
			this.body = body;
		}

		@Override
		public int read() {
			return position < body.length ? body[position++] & 0xFF : -1;
		}

		@Override
		public int read(byte[] target, int offset, int length) throws IOException {
			if (position >= body.length) {
				return -1;
			}
			int count = Math.min(Math.min(length, CHUNK), body.length - position);
			System.arraycopy(body, position, target, offset, count);
			position += count;
			return count;
		}

		@Override
		public boolean isFinished() {
			return position >= body.length;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setReadListener(ReadListener listener) {
			throw new UnsupportedOperationException();
		}
	}
}
