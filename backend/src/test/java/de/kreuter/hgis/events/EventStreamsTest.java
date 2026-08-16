package de.kreuter.hgis.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The stream registry on its own, without a web request around it -- what it accepts, what
 * it refuses, and what it throws away.
 *
 * <p>Deliberately not a Spring test: {@link EventStreams} takes nothing but its
 * properties, so the limits can be exercised at values a running application would never
 * be configured with. The path through a real request is covered by
 * {@link EventStreamControllerTest}.
 */
class EventStreamsTest {

	private static final Duration TIMEOUT = Duration.ofMinutes(7);
	private static final Duration RETRY = Duration.ofSeconds(4);

	private static EventStreams streamsAllowing(int maxStreams) {
		return new EventStreams(new EventProperties(maxStreams, TIMEOUT, RETRY));
	}

	@Test
	@DisplayName("a stream carries the configured timeout, so the container ends it and the client reconnects")
	void aStreamCarriesTheConfiguredTimeout() {
		SseEmitter emitter = streamsAllowing(1).tryOpen().orElseThrow();

		assertThat(emitter.getTimeout()).isEqualTo(TIMEOUT.toMillis());
	}

	@Test
	@DisplayName("streams beyond the limit are refused, and the ones already open keep running")
	void streamsBeyondTheLimitAreRefused() {
		EventStreams streams = streamsAllowing(2);

		assertThat(streams.tryOpen()).isPresent();
		assertThat(streams.tryOpen()).isPresent();

		assertThat(streams.tryOpen()).isEmpty();
		assertThat(streams.openStreams()).isEqualTo(2);
	}

	@Test
	@DisplayName("a refused stream frees no slot: the limit still holds on the next attempt")
	void aRefusedStreamDoesNotCountAgainstItself() {
		EventStreams streams = streamsAllowing(1);
		streams.tryOpen().orElseThrow();

		assertThat(streams.tryOpen()).isEmpty();
		assertThat(streams.tryOpen()).isEmpty();
		assertThat(streams.openStreams()).isEqualTo(1);
	}

	@Test
	@DisplayName("a stream that can no longer be written to is dropped, and the slot comes back")
	void aDeadStreamIsDropped() {
		EventStreams streams = streamsAllowing(1);
		SseEmitter emitter = streams.tryOpen().orElseThrow();
		// What a closed browser tab amounts to from this side: the next write fails.
		emitter.complete();

		streams.publish("project-view-state", "irgendetwas");

		assertThat(streams.openStreams()).isZero();
		assertThat(streams.tryOpen()).isPresent();
	}

	@Test
	@DisplayName("the heartbeat drops a dead stream too -- that is what it is for")
	void theHeartbeatDropsADeadStream() {
		EventStreams streams = streamsAllowing(2);
		SseEmitter dead = streams.tryOpen().orElseThrow();
		Optional<SseEmitter> alive = streams.tryOpen();
		dead.complete();

		streams.heartbeat();

		assertThat(alive).isPresent();
		assertThat(streams.openStreams()).isEqualTo(1);
	}

	@Test
	@DisplayName("a heartbeat without any open stream does nothing at all")
	void aHeartbeatWithoutStreamsIsHarmless() {
		EventStreams streams = streamsAllowing(1);

		streams.heartbeat();

		assertThat(streams.openStreams()).isZero();
	}
}
