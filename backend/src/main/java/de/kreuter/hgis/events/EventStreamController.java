package de.kreuter.hgis.events;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live channel: {@code GET /api/events}, Server-Sent Events.
 *
 * <p>What travels here and why it is shaped this way is written down once, in
 * {@code EventDtos}. In short: every event names a project and the version its working
 * state now stands at, and carries nothing else. Whoever hears one reads the state
 * through the ordinary API.
 *
 * <p>The simplest way to look at it:
 * <pre>curl -N http://localhost:8080/api/events</pre>
 *
 * <h2>Hearing your own change</h2>
 * A client that writes a working state also hears the event its own write produced. If it
 * answered that by reading the state back and applying it, it would be undoing and
 * redoing its own work, and a client that writes on every applied state would never stop.
 * So a writer names itself in {@code X-Hgis-Client} when it writes, and the event carries
 * that name back as {@code origin}: finding its own name there, a client already holds
 * this state and has nothing to do. It is still a state and not a change -- the client
 * is not ignoring the news, it simply already has it.
 *
 * <p>The alternative -- letting every client read back its own writes -- was not taken:
 * it is not merely wasteful, it lets a read overwrite an edit the user made in the
 * meantime, which is visible as a flicker and, worse, as lost work.
 *
 * <h2>When the server is full</h2>
 * A refused client gets 503 with {@code Retry-After}. A browser's own {@code EventSource}
 * treats any non-200 as final and does not retry, so the client's own reconnect handling
 * is what brings it back -- deliberately, because a client that reconnects instantly
 * against a full server is the problem, not the cure.
 */
@RestController
class EventStreamController {

	/** How long a refused client should wait, in seconds. Long enough to be no load at all. */
	private static final String RETRY_AFTER_SECONDS = "30";

	private final EventStreams streams;

	EventStreamController(EventStreams streams) {
		this.streams = streams;
	}

	@GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	ResponseEntity<SseEmitter> stream() {
		return streams.tryOpen()
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
						.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
						.build());
	}
}
