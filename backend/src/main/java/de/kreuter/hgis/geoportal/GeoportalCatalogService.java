package de.kreuter.hgis.geoportal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Holds the merged catalog in memory, per decision E5: no schedule, no background job. The
 * first call of any session pays for the load (the two upstream files together are almost
 * 8&nbsp;MB, plan section 3.5); every call after that, across every session, is served from
 * the same held copy until something calls {@link #refresh()}.
 */
@Service
class GeoportalCatalogService {

	private static final Logger log = LoggerFactory.getLogger(GeoportalCatalogService.class);

	private final CatalogLoader loader;
	private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

	/**
	 * One load at a time, across every request thread. A lock rather than a synchronized
	 * method because the two entry points need different things from it: {@link #current()}
	 * checks again after acquiring it and usually does not load at all, while {@link
	 * #refresh()} always does.
	 */
	private final ReentrantLock loadLock = new ReentrantLock();

	GeoportalCatalogService(CatalogLoader loader) {
		this.loader = loader;
	}

	/**
	 * The listing and the lookup are not the same set (CONTRACT.md 11.9): a service listed
	 * as one row keeps its collections out of {@code entries}, or the eight such services
	 * would put 475 rows into a dialog that is meant to show eight -- but every one of those
	 * collections is in {@code byId}, because the very next request after picking one asks
	 * for its detail by its own id, and the import after that does the same.
	 *
	 * @param entries what the catalog listing shows, in load order
	 * @param byId    every entry the listing shows <em>and</em> every collection of a service
	 *                listed as one row
	 */
	record Snapshot(Instant fetchedAt, List<GeoportalCatalogEntry> entries, Map<String, GeoportalCatalogEntry> byId) {

		Snapshot {
			entries = List.copyOf(entries);
			byId = Map.copyOf(byId);
		}
	}

	/**
	 * CONTRACT.md 11.2: loads once when nothing is held yet, serves the held copy otherwise.
	 *
	 * <p>The "load once" is what {@link #loadLock} is for. Reading the field and filling it
	 * are two steps, and two browser windows opened after a restart run them interleaved:
	 * both find nothing held and both start their own load of the same 7.6&nbsp;MB. The
	 * second one waits here instead and finds the first one's result.
	 */
	Snapshot current() {
		Snapshot existing = snapshot.get();
		if (existing != null) {
			return existing;
		}
		loadLock.lock();
		try {
			Snapshot loaded = snapshot.get();
			return loaded != null ? loaded : load();
		}
		finally {
			loadLock.unlock();
		}
	}

	/**
	 * CONTRACT.md 11.3: always re-fetches both upstream files, even when a copy is held --
	 * that is the whole point of the button (E5).
	 *
	 * <p>Serialised against every other load for a second reason: two overlapping refreshes
	 * finish in whatever order the network decides, so without the lock the older result
	 * could be the one that ends up held, and the button would have made the catalog
	 * <em>older</em>.
	 */
	Snapshot refresh() {
		loadLock.lock();
		try {
			return load();
		}
		finally {
			loadLock.unlock();
		}
	}

	/**
	 * Falls back to the previous snapshot if one exists and the fetch fails (plan section
	 * 7.2) -- a transient outage must not empty a dialog that was working a moment ago; only
	 * a first-ever load with nothing to fall back to propagates the failure.
	 *
	 * <p>Callers hold {@link #loadLock}.
	 */
	private Snapshot load() {
		try {
			List<GeoportalCatalogEntry> entries = loader.load();
			Snapshot fresh = new Snapshot(Instant.now(), entries, index(entries));
			snapshot.set(fresh);
			return fresh;
		}
		catch (CatalogLoadException e) {
			Snapshot existing = snapshot.get();
			if (existing != null) {
				log.warn("Katalogaktualisierung fehlgeschlagen, halte den Stand vom {}", existing.fetchedAt(), e);
				return existing;
			}
			throw e;
		}
	}

	Optional<GeoportalCatalogEntry> find(String id) {
		return Optional.ofNullable(current().byId().get(id));
	}

	/**
	 * Indexes the listing and, for a service listed as one row, its collections along with
	 * it -- those are never listed but must still be findable, since choosing one in the
	 * detail pane is exactly what a client does before asking for its detail or importing it.
	 */
	private static Map<String, GeoportalCatalogEntry> index(List<GeoportalCatalogEntry> entries) {
		Map<String, GeoportalCatalogEntry> byId = new LinkedHashMap<>();
		for (GeoportalCatalogEntry entry : entries) {
			byId.putIfAbsent(entry.id(), entry); // first wins on an id collision (defensive)
			for (GeoportalCatalogEntry collection : entry.collections()) {
				byId.putIfAbsent(collection.id(), collection);
			}
		}
		return byId;
	}
}
