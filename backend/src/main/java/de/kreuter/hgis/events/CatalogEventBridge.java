package de.kreuter.hgis.events;

import de.kreuter.hgis.catalog.CatalogChanged;
import de.kreuter.hgis.catalog.ProjectViewStateChanged;
import de.kreuter.hgis.events.dto.EventDtos;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns what the catalog says happened into what goes over the wire.
 *
 * <p>The one thing this class exists for is <em>when</em>: after commit, never before. An
 * event says a project stands at a version, and the only sensible answer to it is to read
 * that project. A listener that heard it while the writing transaction was still open
 * would read the previous state and believe it current -- and, since no further event is
 * coming, stay wrong.
 *
 * <p>{@code AFTER_COMMIT} also means a write that rolls back produces no event at all,
 * which is right: nothing changed.
 *
 * <p>A second kind of event is a second method here. That is the whole of what adding one
 * costs on this side -- neither {@link EventStreams} nor the endpoint has to know.
 */
@Component
class CatalogEventBridge {

	private final EventStreams streams;
	private final JdbcClient jdbc;

	CatalogEventBridge(EventStreams streams, JdbcClient jdbc) {
		this.streams = streams;
		this.jdbc = jdbc;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onProjectViewStateChanged(ProjectViewStateChanged changed) {
		streams.publish(EventDtos.EventNames.PROJECT_VIEW_STATE,
				new EventDtos.ProjectViewState(changed.projectId(), changed.version(), changed.origin()));
	}

	/**
	 * Unlike {@link #onProjectViewStateChanged}, {@link CatalogChanged} carries no version
	 * of its own -- {@code catalog_version} is bumped by a database trigger, not by the
	 * write path that published this event, so nothing upstream ever held the fresh
	 * number (see {@code CatalogChanged}'s own javadoc). Reading it back here is safe
	 * precisely because this method only ever runs after commit: the write this event
	 * describes has already landed by the time this read happens, so it always sees at
	 * least that write's own bump, never an earlier value. Seeing a slightly newer one --
	 * a second write already landed in the meantime -- is equally fine: the channel
	 * reports a state, not a diff, and the later state is simply the current truth.
	 *
	 * <p>The project can, in principle, already be gone by the time this runs -- deleted in
	 * a transaction that committed between the write this event describes and this read.
	 * Nothing is published then: there is no one left to read the version back for.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCatalogChanged(CatalogChanged changed) {
		jdbc.sql("SELECT catalog_version FROM gis_meta.project WHERE id = :id")
				.param("id", changed.projectId())
				.query(Long.class)
				.optional()
				.ifPresent(version -> streams.publish(EventDtos.EventNames.PROJECT_CATALOG,
						new EventDtos.ProjectCatalog(changed.projectId(), version, changed.origin())));
	}
}
