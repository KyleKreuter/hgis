package de.kreuter.hgis.export;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Caps the body of a POST export before anything tries to parse it.
 *
 * <p>{@link FidSelection#MAX_FIDS} already refuses a selection of more than a hundred
 * thousand rows, but it can only refuse one it has been handed: by the time the record is
 * constructed, Jackson has read the whole body and built a {@code List<Long>} out of it. A
 * request of a few hundred megabytes is therefore a few hundred megabytes of heap before
 * the first check runs, and repeating it is all it takes. The limit has to be in front of
 * the parser, not behind it.
 *
 * <p>Which rules out {@code Content-Length}: a chunked request has none, and a declared
 * one is a claim, not a fact. The body is instead read here, in bounded steps, and the
 * request is refused the moment the count passes the limit -- whether or not it announced
 * a size. What was read is handed on in a wrapper, so the converter downstream still sees
 * an ordinary request; below the limit this costs one buffer that Jackson's parse would
 * have needed anyway.
 *
 * <p>Only the export POST is treated this way. Every other request, of any method or path,
 * passes untouched -- {@code multipart} uploads in particular, which are measured in
 * hundreds of megabytes on purpose and have their own limits.
 */
@Component
class ExportBodyLimitFilter extends OncePerRequestFilter {

	/**
	 * Room for the largest legal selection and nothing beyond it. A hundred thousand row
	 * ids of twenty digits with their commas come to some 2.1 MB; four gives that a wide
	 * margin for whitespace and still bounds one request to something a server can hold
	 * several of at once.
	 */
	static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

	/** The one path this applies to, relative to the context. */
	private static final String PATH_PREFIX = "/api/layers/";
	private static final String PATH_SUFFIX = "/export.geojson";

	private static final int COPY_CHUNK_BYTES = 8 * 1024;

	private static final Logger log = LoggerFactory.getLogger(ExportBodyLimitFilter.class);

	private final ObjectMapper objectMapper;

	ExportBodyLimitFilter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!HttpMethod.POST.matches(request.getMethod())) {
			return true;
		}
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !path.startsWith(PATH_PREFIX) || !path.endsWith(PATH_SUFFIX);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		// A declared length over the limit is settled without reading a byte. It is not
		// the guarantee -- the loop below is -- only the cheap half of it.
		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			reject(response, request.getContentLengthLong());
			return;
		}

		Optional<byte[]> body = readWithinLimit(request.getInputStream());
		if (body.isEmpty()) {
			reject(response, -1);
			return;
		}

		chain.doFilter(new BufferedBodyRequest(request, body.get()), response);
	}

	/**
	 * Reads the body in bounded steps.
	 *
	 * @return the bytes, or empty as soon as the count passes the limit -- the rest of the
	 *         stream is left unread on purpose, since reading it would be the very thing
	 *         being refused
	 */
	private static Optional<byte[]> readWithinLimit(InputStream in) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream(COPY_CHUNK_BYTES);
		byte[] chunk = new byte[COPY_CHUNK_BYTES];

		int read;
		while ((read = in.read(chunk)) != -1) {
			if (buffer.size() + read > MAX_BODY_BYTES) {
				return Optional.empty();
			}
			buffer.write(chunk, 0, read);
		}
		return Optional.of(buffer.toByteArray());
	}

	/**
	 * @param declaredLength what the request claimed, or -1 when it claimed nothing and
	 *                       the limit was reached while reading
	 */
	private void reject(HttpServletResponse response, long declaredLength) throws IOException {
		log.info("Export-POST abgelehnt, Körper über {} Bytes (angekündigt: {})",
				MAX_BODY_BYTES, declaredLength < 0 ? "keine Angabe" : declaredLength);

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
				"Der Anfragekörper darf höchstens " + MAX_BODY_BYTES + " Bytes groß sein");
		problem.setTitle("Anfrage zu groß");

		byte[] payload = objectMapper.writeValueAsBytes(problem);

		response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentLength(payload.length);
		// The connection still carries a body nobody is going to read. Tomcat swallows a
		// bounded remainder and drops the connection for a 413 by itself, which is the
		// right end for a request refused over its size and not something to arrange here.
		response.getOutputStream().write(payload);
		response.flushBuffer();
	}

	/**
	 * The request as the rest of the chain sees it: same headers, body served from what
	 * was already read. The length is restated as the number of bytes actually present,
	 * which is the first time a chunked request has one.
	 */
	private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		private BufferedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}

		@Override
		public ServletInputStream getInputStream() {
			return new BufferedBodyStream(body);
		}

		@Override
		public BufferedReader getReader() {
			Charset charset = getCharacterEncoding() == null
					? StandardCharsets.UTF_8
					: Charset.forName(getCharacterEncoding());
			return new BufferedReader(new InputStreamReader(getInputStream(), charset));
		}
	}

	private static final class BufferedBodyStream extends ServletInputStream {

		private final ByteArrayInputStream delegate;

		private BufferedBodyStream(byte[] body) {
			this.delegate = new ByteArrayInputStream(body);
		}

		@Override
		public int read() {
			return delegate.read();
		}

		@Override
		public int read(byte[] target, int offset, int length) {
			return delegate.read(target, offset, length);
		}

		@Override
		public int available() {
			return delegate.available();
		}

		@Override
		public boolean isFinished() {
			return delegate.available() == 0;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setReadListener(ReadListener listener) {
			// Non-blocking reads are for a stream that is still arriving; this one is a
			// byte array and has arrived.
			throw new UnsupportedOperationException(
					"Der gepufferte Anfragekörper wird nur blockierend gelesen");
		}
	}
}
