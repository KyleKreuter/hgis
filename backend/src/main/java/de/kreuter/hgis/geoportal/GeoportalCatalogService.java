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

	record Snapshot(Instant fetchedAt, Map<String, GeoportalCatalogEntry> byId) {

		List<GeoportalCatalogEntry> entries() {
			return List.copyOf(byId.values());
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
			Snapshot fresh = new Snapshot(Instant.now(), toMap(entries));
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

	private static Map<String, GeoportalCatalogEntry> toMap(List<GeoportalCatalogEntry> entries) {
		Map<String, GeoportalCatalogEntry> byId = new LinkedHashMap<>();
		for (GeoportalCatalogEntry entry : entries) {
			byId.putIfAbsent(entry.id(), entry); // first wins on an id collision (defensive)
		}
		return byId;
	}
}
