package de.kreuter.hgis.geoportal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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

	GeoportalCatalogService(CatalogLoader loader) {
		this.loader = loader;
	}

	record Snapshot(Instant fetchedAt, Map<String, GeoportalCatalogEntry> byId) {

		List<GeoportalCatalogEntry> entries() {
			return List.copyOf(byId.values());
		}
	}

	/** CONTRACT.md 11.2: loads once when nothing is held yet, serves the held copy otherwise. */
	Snapshot current() {
		Snapshot existing = snapshot.get();
		return existing != null ? existing : refresh();
	}

	/**
	 * CONTRACT.md 11.3: always re-fetches both upstream files. Falls back to the previous
	 * snapshot if one exists and the fetch fails (plan section 7.2) -- a transient outage
	 * must not empty a dialog that was working a moment ago; only a first-ever load with
	 * nothing to fall back to propagates the failure.
	 */
	Snapshot refresh() {
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
