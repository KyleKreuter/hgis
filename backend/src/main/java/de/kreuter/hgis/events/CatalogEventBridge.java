package de.kreuter.hgis.events;

import de.kreuter.hgis.catalog.CatalogChanged;
import de.kreuter.hgis.catalog.ProjectViewStateChanged;
import de.kreuter.hgis.catalog.ProjectViewportChanged;
import de.kreuter.hgis.events.dto.EventDtos;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

	/**
	 * The {@code catalog_version} this class last actually published, per project -- see
	 * {@link #onCatalogChanged} for why. In-memory, per JVM, on purpose; three questions
	 * worth asking of exactly that choice, all with the same answer:
	 *
	 * <ul>
	 * <li><b>Unbounded growth?</b> One entry per project ever touched since this process
	 *     started, never evicted -- a UUID and a long each. hGIS runs single-tenant today
	 *     (CONTRACT.md's own "bewusst spaeter" on multi-user access control); a project
	 *     count that made this worth engineering around is not a shape this deployment
	 *     produces.
	 * <li><b>Restart?</b> Empties the map. The next event for a project then finds no
	 *     entry and publishes -- once more than strictly necessary, never fewer. The map
	 *     can make this class over-announce; it can never make it under-announce.
	 * <li><b>Several instances behind a load balancer?</b> Would defeat the deduplication
	 *     for whichever instance did not just handle the write -- but {@link EventStreams}
	 *     is exactly as single-instance as this map already: its open streams live in one
	 *     JVM's memory too, so a browser connected to a different instance would never see
	 *     any event at all, from this method or {@link #onProjectViewStateChanged} alike.
	 *     Several instances break the whole channel first, long before this map's own
	 *     locality would be the thing to fix.
	 * </ul>
	 */
	private final ConcurrentHashMap<UUID, Long> lastPublishedCatalogVersion = new ConcurrentHashMap<>();

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
	 * No de-duplication here, unlike {@link #onCatalogChanged}: {@link
	 * ProjectViewportChanged} is only ever published once per request, by {@code
	 * ProjectService#update} itself, so there is no second write within the same
	 * transaction this method could otherwise be asked to announce twice.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onProjectViewportChanged(ProjectViewportChanged changed) {
		streams.publish(EventDtos.EventNames.PROJECT_VIEWPORT,
				new EventDtos.ProjectViewport(changed.projectId(), changed.origin()));
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
	 *
	 * <p>Found on review: {@link CatalogTouch} announces on every write <em>attempt</em>
	 * {@code ChangeLogService.record} sees, not on every write that actually changed a row
	 * -- a {@code PATCH} that sets a field to the value it already has, or an empty one,
	 * still logs {@code layer.update} and still calls {@code touch}, but Hibernate's own
	 * dirty checking skips the {@code UPDATE} entirely, so the trigger never fires and
	 * {@code catalog_version} does not move. Fixing that further upstream would mean
	 * teaching every write path -- or {@code ChangeLogService.record} itself -- to know
	 * whether its own write actually did anything, which is exactly the kind of per-path
	 * bookkeeping the trigger exists to make unnecessary. Comparing the version read here
	 * against the one last published for this project is cheaper and lives in the one place
	 * that already reads it: a no-op write still reaches this method, but is silently
	 * dropped rather than announced a second time under the same version -- which is what
	 * actually enforces "an event reports a state" rather than merely relying on every
	 * caller to have earned the event it triggered.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCatalogChanged(CatalogChanged changed) {
		jdbc.sql("SELECT catalog_version FROM gis_meta.project WHERE id = :id")
				.param("id", changed.projectId())
				.query(Long.class)
				.optional()
				.filter(version -> versionIsNewFor(changed.projectId(), version))
				.ifPresent(version -> streams.publish(EventDtos.EventNames.PROJECT_CATALOG,
						new EventDtos.ProjectCatalog(changed.projectId(), version, changed.origin())));
	}

	/**
	 * @return whether {@code version} differs from the one last recorded for {@code
	 *     projectId} -- and, in the same atomic step, records it as the new last-known
	 *     value regardless of the answer, so two concurrent calls for the same project
	 *     never both see "unchanged" for two genuinely different versions.
	 */
	private boolean versionIsNewFor(UUID projectId, long version) {
		Long previous = lastPublishedCatalogVersion.put(projectId, version);
		return previous == null || previous != version;
	}
}
