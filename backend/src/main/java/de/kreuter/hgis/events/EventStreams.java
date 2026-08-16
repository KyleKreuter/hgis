package de.kreuter.hgis.events;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Every open live channel, and the one way anything is written to them.
 *
 * <h2>One stream, not one per project</h2>
 * A browser holds only a handful of connections per origin at a time -- six over HTTP/1.1
 * -- and a permanently open stream occupies one of them for as long as the page lives. A
 * stream per project would spend that budget on projects the tab is not even showing, and
 * the tab would start starving its own tile and attribute requests. So there is exactly
 * one channel per client, every event names the project it is about, and a receiver
 * ignores what does not concern it. An event is a handful of bytes, so hearing about
 * another project costs nothing worth avoiding.
 *
 * <h2>How long a stream lives</h2>
 * {@code streamTimeout} ends a connection on purpose. A client whose network vanished
 * without closing its socket is indistinguishable from an idle one, and without an end
 * such a connection would be held for as long as the server runs. SSE clients reconnect
 * by themselves, so ending one is not a loss -- and because every event states where a
 * project stands rather than what changed, a client that missed something during the gap
 * is put right by the next event it hears. The heartbeat below closes the same hole from
 * the other side: it fails as soon as the peer is really gone, which frees the slot long
 * before the timeout would.
 *
 * <h2>How many streams</h2>
 * {@code maxStreams} is the point at which the server stops accepting more. It is not a
 * licence count -- a browser tab holds one stream -- but the line past which something is
 * opening channels and never closing them. See {@link EventStreamController} for what a
 * refused client is told.
 */
@Component
public class EventStreams {

	private static final Logger log = LoggerFactory.getLogger(EventStreams.class);

	/**
	 * Idle streams get one of these; anything that is no longer connected fails on it and
	 * is dropped. Well under any usual proxy or load balancer idle timeout, and a comment
	 * rather than an event so no receiver ever has to know it exists.
	 */
	private static final String HEARTBEAT = "hb";

	private final EventProperties properties;

	/** Every open stream. Written from request threads, read from the publishing thread. */
	private final Set<Stream> streams = ConcurrentHashMap.newKeySet();

	EventStreams(EventProperties properties) {
		this.properties = properties;
	}

	/**
	 * Opens a live channel, unless there are already {@code maxStreams} of them.
	 *
	 * <p>Synchronized so the limit is exact under a burst of connections: without it,
	 * every one of them could see the same count below the limit and all of them get in.
	 * Opening happens once per client per {@code streamTimeout}, so nothing waits here
	 * that matters.
	 *
	 * @return the emitter to return from the controller, or empty when the server is full
	 */
	synchronized Optional<SseEmitter> tryOpen() {
		if (streams.size() >= properties.maxStreams()) {
			log.warn("Live-Kanal abgelehnt. Die Grenze offener Ströme ({}) ist erreicht.", properties.maxStreams());
			return Optional.empty();
		}

		SseEmitter emitter = new SseEmitter(properties.streamTimeout().toMillis());
		Stream stream = new Stream(emitter);
		streams.add(stream);
		// All three fire for a stream that is over, and more than one of them can fire for
		// the same stream. Removing twice is harmless; leaving one out is not.
		emitter.onCompletion(() -> streams.remove(stream));
		emitter.onTimeout(() -> streams.remove(stream));
		emitter.onError(error -> streams.remove(stream));

		// Sent immediately, and not only to name the retry interval: it is what flushes the
		// response headers, so the client's connection counts as open right away rather
		// than at whatever moment the first real event happens to arrive.
		if (!stream.send(SseEmitter.event()
				.reconnectTime(properties.retry().toMillis())
				.comment("Live-Kanal offen"))) {
			streams.remove(stream);
		}
		log.debug("Live-Kanal geöffnet. Offene Ströme: {}", streams.size());
		return Optional.of(emitter);
	}

	/**
	 * Sends one event to every open stream.
	 *
	 * <p>A stream that cannot be written to is dropped rather than retried: the peer is
	 * gone, and it will open a new stream when it comes back. Nothing is queued for it in
	 * the meantime -- an event states where a project stands, so the current one always
	 * replaces every missed one, and there is nothing to catch up on.
	 *
	 * @param name    the SSE {@code event:} name, from {@code EventDtos.EventNames}
	 * @param payload serialised as JSON by the application's own message converters
	 */
	public void publish(String name, Object payload) {
		// A builder per stream, never one shared: building it is what terminates the event,
		// and building the same one twice appends a second terminator. Two streams would
		// then be sent different bytes, the later one malformed.
		streams.removeIf(stream -> !stream.send(
				SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON)));
	}

	/**
	 * Keeps idle connections alive and, more importantly, notices dead ones: nothing else
	 * writes to a stream between two events, and a socket whose other end is gone only
	 * reveals that when something is written to it.
	 */
	@Scheduled(fixedDelayString = "${hgis.events.heartbeat:25s}")
	void heartbeat() {
		if (streams.isEmpty()) {
			return;
		}
		streams.removeIf(stream -> !stream.send(SseEmitter.event().comment(HEARTBEAT)));
	}

	/** How many streams are open right now. For tests and for the log line above. */
	public int openStreams() {
		return streams.size();
	}

	/**
	 * One connection, with the lock that keeps two writers off it.
	 *
	 * <p>{@link SseEmitter} is not safe for concurrent sends, and there are two writers by
	 * construction: whichever thread committed a change, and the heartbeat's. Interleaving
	 * their bytes would produce a stream no client can parse. Identity equality is what is
	 * wanted here -- two connections are never "the same" -- so no equals/hashCode.
	 */
	private static final class Stream {

		private final SseEmitter emitter;
		private final ReentrantLock lock = new ReentrantLock();

		private Stream(SseEmitter emitter) {
			this.emitter = emitter;
		}

		/** @return false when this stream is finished and should be dropped */
		private boolean send(SseEmitter.SseEventBuilder event) {
			lock.lock();
			try {
				emitter.send(event);
				return true;
			}
			catch (IOException | IllegalStateException ex) {
				// A closed browser tab reaches here on the next write, which is the normal
				// end of a stream and not worth more than a debug line. IllegalStateException
				// is the same fact from the other side: Spring has already completed the
				// emitter, e.g. after its timeout.
				log.debug("Live-Kanal geschlossen: {}", ex.getMessage());
				return false;
			}
			finally {
				lock.unlock();
			}
		}
	}
}
