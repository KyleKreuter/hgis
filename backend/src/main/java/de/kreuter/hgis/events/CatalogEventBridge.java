package de.kreuter.hgis.events;

import de.kreuter.hgis.catalog.ProjectViewStateChanged;
import de.kreuter.hgis.events.dto.EventDtos;
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

	CatalogEventBridge(EventStreams streams) {
		this.streams = streams;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onProjectViewStateChanged(ProjectViewStateChanged changed) {
		streams.publish(EventDtos.EventNames.PROJECT_VIEW_STATE,
				new EventDtos.ProjectViewState(changed.projectId(), changed.version(), changed.origin()));
	}
}
