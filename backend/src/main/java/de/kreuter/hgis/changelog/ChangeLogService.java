package de.kreuter.hgis.changelog;

import de.kreuter.hgis.catalog.CatalogTouch;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.changelog.dto.ChangeLogDtos;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and reads the change log every layer- and feature-write leaves behind
 * (CONTRACT.md "Schreibstufe" 1.2).
 *
 * <p>{@link #record} is also where almost every catalog-changing write path announces
 * itself to the live channel (plan "Der Live-Kanal meldet auch Datenaenderungen"): nearly
 * all of them already call it for the change log's own sake, so hooking {@link
 * CatalogTouch} in here reaches them for free instead of asking each one to remember a
 * second call. The two write paths that never log to the change log at all -- {@code
 * LayerService#reorder} and {@code LayerFieldService#renameField} -- call
 * {@link CatalogTouch} directly instead.
 *
 * <p>{@link #recordWithoutTouch} is the one deliberate exception, for {@code
 * ProjectDuplicateTransactions#copyLayer} alone: it runs once per layer, each its own
 * transaction, so hooking {@link CatalogTouch} into it the ordinary way would turn one
 * duplicate of several layers into that many separate catalog-changed events -- each
 * showing the target project only partly copied. The change log still gets every entry,
 * one per layer, exactly as before; only the live-channel announcement is deferred to
 * {@code ProjectDuplicateTransactions#complete}, which is called once and reaches the
 * channel with the finished copy -- the same "one operation, one event" rule {@code
 * ImportTransactions#complete} already follows for an import's many batches.
 */
@Service
public class ChangeLogService {

	/** Same ceiling and rejection form as every other sized listing -- see FeatureQueryService.MAX_PAGE_SIZE. */
	private static final int MAX_SIZE = 1000;

	private final ChangeLogRepository repository;
	private final ProjectRepository projectRepository;
	private final CatalogTouch catalogTouch;

	ChangeLogService(ChangeLogRepository repository, ProjectRepository projectRepository,
			CatalogTouch catalogTouch) {
		this.repository = repository;
		this.projectRepository = projectRepository;
		this.catalogTouch = catalogTouch;
	}

	/**
	 * Appends one entry. Runs inside the caller's transaction; it opens none of its
	 * own -- the same discipline {@code LayerBookkeeping.recount} follows, since a log
	 * entry has to land in the exact same transaction as the write it describes, or a
	 * rolled-back write would leave a log entry for something that never happened.
	 *
	 * @param layerId       null only for {@link ChangeLogAction#LAYER_PURGE}, logged
	 *                      after the layer row is already gone -- see {@link
	 *                      de.kreuter.hgis.catalog.LayerService#purge}
	 * @param layerName     the layer's current name, captured now because {@code
	 *                      layerId} will read back null forever once the layer is purged
	 * @param action        one of {@link ChangeLogAction}'s ten tokens
	 * @param clientName    the {@code X-Hgis-Client} of whoever wrote it, or null
	 * @param affectedCount how many objects this write touched; always {@code > 0} --
	 *                      callers must not log a write that touched nothing
	 * @param deletedRowsJson the removed rows as a JSON array text, only for {@link
	 *                      ChangeLogAction#FEATURE_DELETE}; null for every other action
	 */
	public void record(UUID projectId, UUID layerId, String layerName, String action,
			String clientName, int affectedCount, String deletedRowsJson) {
		repository.save(new ChangeLogEntry(
				projectId, layerId, layerName, action, clientName, affectedCount, deletedRowsJson));
		catalogTouch.touch(projectId, clientName);
	}

	/**
	 * Same entry as {@link #record}, but does not announce it to the live channel -- see
	 * this class's own javadoc for the one caller this exists for and why.
	 */
	public void recordWithoutTouch(UUID projectId, UUID layerId, String layerName, String action,
			String clientName, int affectedCount, String deletedRowsJson) {
		repository.save(new ChangeLogEntry(
				projectId, layerId, layerName, action, clientName, affectedCount, deletedRowsJson));
	}

	/**
	 * The protocol for one project, newest first.
	 *
	 * @param includeDeletedRows whether to carry each entry's captured rows along -- off
	 *     by default, the same reasoning as {@code GET .../features}'s own {@code
	 *     geometry} parameter: a {@code feature.delete} entry can hold up to {@code
	 *     EditService.MAX_BATCH} rows of geometry and attributes, and a page of {@code
	 *     size} such entries would otherwise make an ordinary browsing request cost
	 *     however much data the busiest write in the project's history happened to touch
	 * @throws NotFoundException if no such project exists
	 * @throws BadRequestException when size is outside 1..{@value #MAX_SIZE}
	 */
	@Transactional(readOnly = true)
	public List<ChangeLogDtos.Entry> list(UUID projectId, int size, boolean includeDeletedRows) {
		if (!projectRepository.existsById(projectId)) {
			throw new NotFoundException("Projekt " + projectId + " existiert nicht");
		}
		if (size < 1 || size > MAX_SIZE) {
			throw new BadRequestException(
					"size muss zwischen 1 und " + MAX_SIZE + " liegen. Angefragt waren " + size + ".");
		}

		return repository.findByProjectIdOrderByOccurredAtDescIdDesc(projectId, PageRequest.of(0, size))
				.stream()
				.map(entry -> toDto(entry, includeDeletedRows))
				.toList();
	}

	private static ChangeLogDtos.Entry toDto(ChangeLogEntry entry, boolean includeDeletedRows) {
		return new ChangeLogDtos.Entry(
				entry.getId(), entry.getOccurredAt(), entry.getLayerId(), entry.getLayerName(),
				entry.getAction(), entry.getClientName(), entry.getAffectedCount(),
				includeDeletedRows ? entry.getDeletedRows() : null);
	}
}
