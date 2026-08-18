package de.kreuter.hgis.catalog;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Announces that one project's catalog changed -- at most once per transaction, however
 * many write paths call {@link #touch} for it and however many times each of them does.
 *
 * <p>The de-duplication earns its keep because {@code ChangeLogService.record} -- the one
 * choke point almost every catalog-changing write path already goes through, and where
 * this class is called from for exactly that reason -- is not itself called once per
 * transaction. {@code ImportTransactions#complete} logs both {@code layer.create} and
 * {@code feature.insert} for the same import inside the same transaction; an edit batch
 * with creates, updates and deletes logs up to three entries. Without the guard here, such
 * a transaction would publish {@link CatalogChanged} two or three times over. Harmless
 * under the live channel's own rules -- an event reports a state, so hearing it again only
 * costs a repeated, idempotent reread -- but still the kind of needless chatter the plan
 * this class implements asks to avoid: one import is one catalog event, not several, and
 * emphatically not one per object it wrote.
 *
 * <p>The guard is scoped to the current transaction through {@link
 * TransactionSynchronizationManager}'s resource map, not a field on this singleton bean --
 * a field would leak state across whatever unrelated requests happen to run concurrently,
 * which a transaction-scoped resource cannot do.
 *
 * <p>The two write paths that touch a project's catalog without ever calling {@code
 * ChangeLogService.record} -- {@code LayerService#reorder} and {@code
 * LayerFieldService#renameField}, neither of which the change log's action list covers --
 * call this directly instead.
 */
@Component
public class CatalogTouch {

	/** Unique to this bean; only ever used as a resource-map key, never dereferenced. */
	private static final Object RESOURCE_KEY = new Object();

	private final ApplicationEventPublisher events;

	CatalogTouch(ApplicationEventPublisher events) {
		this.events = events;
	}

	/**
	 * @param projectId the project whose catalog changed
	 * @param origin    the {@code X-Hgis-Client} of whoever wrote it, or null -- see
	 *     {@link CatalogChanged#origin}. When {@link #touch} is called more than once for
	 *     the same project within one transaction, only the first call's origin travels
	 *     on: every write inside one transaction is, on every path this application has
	 *     today, driven by the single client that made the request, so the two never
	 *     actually disagree.
	 */
	public void touch(UUID projectId, String origin) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			// No transaction to key the guard on -- publish outright rather than silently
			// doing nothing. Reached by a test calling this directly; every real write path
			// runs inside a @Transactional method.
			events.publishEvent(new CatalogChanged(projectId, origin));
			return;
		}
		if (announcedInThisTransaction().add(projectId)) {
			events.publishEvent(new CatalogChanged(projectId, origin));
		}
	}

	@SuppressWarnings("unchecked")
	private Set<UUID> announcedInThisTransaction() {
		Set<UUID> announced = (Set<UUID>) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
		if (announced != null) {
			return announced;
		}

		Set<UUID> created = new HashSet<>();
		TransactionSynchronizationManager.bindResource(RESOURCE_KEY, created);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY);
			}
		});
		return created;
	}
}
