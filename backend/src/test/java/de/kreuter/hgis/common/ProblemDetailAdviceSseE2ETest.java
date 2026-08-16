package de.kreuter.hgis.common;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.kreuter.hgis.TestcontainersConfiguration;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.slf4j.LoggerFactory;

/**
 * A second production trigger for the exact handler {@link ProblemDetailAdvice#handleClientGone}
 * exists for (tiles: CONTRACT.md tile size finding), found independently by a review of
 * the live channel ({@code events/}, never modified by this test -- it is already correct,
 * catching its own ordinary close locally in {@code EventStreams.Stream.send()} and logging
 * it at debug). This one arrives a different way: a client whose socket dies without a
 * normal close (killed, not closed) is invisible to that local catch -- Tomcat's own async
 * error listener is what notices, and it notices on the next write to that connection, not
 * before. Real production code path, no {@code events/} file touched: only this test, from
 * outside that package, hitting {@code GET /api/events} over a real socket.
 *
 * <p>{@code hgis.events.heartbeat} is overridden to 200 ms so "the next write" arrives
 * within the test's patience rather than the real 25 s default. Even then this is a race
 * between two notification paths and does not fire on every attempt (a manual run against
 * this fix found 12 out of 12 hard kills), so this test retries a bounded number of times
 * and asserts on the first attempt that actually reaches {@link ProblemDetailAdvice}'s
 * logger -- not on every attempt succeeding.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "hgis.events.heartbeat=200ms")
class ProblemDetailAdviceSseE2ETest {

	private static final int MAX_ATTEMPTS = 20;

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("a hard-killed live-channel socket is logged at info, without a stack trace, not as an unhandled exception")
	void hardKilledSseConnectionIsLoggedQuietly() throws Exception {
		Logger adviceLogger = (Logger) LoggerFactory.getLogger(ProblemDetailAdvice.class);
		ListAppender<ILoggingEvent> events = new ListAppender<>();
		events.start();
		adviceLogger.addAppender(events);

		try {
			for (int attempt = 1; attempt <= MAX_ATTEMPTS && events.list.isEmpty(); attempt++) {
				killOneConnectionHard();
				// Past one heartbeat cycle: long enough for the scheduled sweep to reach
				// this connection and, on the attempts where the async path wins the race,
				// for Tomcat's error listener to report it back up to the advice.
				Thread.sleep(400);
			}
		}
		finally {
			adviceLogger.detachAppender(events);
		}

		assertThat(events.list)
				.as("mindestens einer von " + MAX_ATTEMPTS + " hart abgebrochenen Verbindungen "
						+ "haette ueber den Async-Fehler-Listener bei ProblemDetailAdvice ankommen muessen")
				.isNotEmpty();

		ILoggingEvent event = events.list.get(0);
		assertThat(event.getLevel()).isEqualTo(Level.INFO);
		assertThat(event.getThrowableProxy())
				.as("kein Stacktrace fuer einen abgebrochenen Client -- das waere die Tomcat-Schreib-Kette, kein eigener Fehler")
				.isNull();
	}

	/** Opens {@code GET /api/events}, waits for it to be accepted, then severs it with RST. */
	private void killOneConnectionHard() throws IOException, InterruptedException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("localhost", port), 2000);
			socket.getOutputStream().write(
					("GET /api/events HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n")
							.getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().flush();
			// Wait for the opening comment SseEmitter flushes immediately (EventStreams.
			// tryOpen), so the connection genuinely counts as an open stream before it dies
			// -- killing it before the server even registered it would prove nothing.
			socket.getInputStream().read(new byte[256]);
			socket.setSoLinger(true, 0);
		}
	}
}
