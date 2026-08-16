package de.kreuter.hgis.catalog;

import de.kreuter.hgis.changelog.ChangeLogAction;
import de.kreuter.hgis.changelog.ChangeLogService;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.common.Uuid7;
import de.kreuter.hgis.jobs.JobService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Transaction boundaries for {@link ProjectDuplicateService}. */
@Component
class ProjectDuplicateTransactions {

	private static final int PROJECT_NAME_MAX_LENGTH = 200;
	record Start(UUID targetProjectId, List<UUID> sourceLayerIds, long totalFeatures) {}

	private final ProjectRepository projectRepository;
	private final LayerRepository layerRepository;
	private final LayerFieldRepository fieldRepository;
	private final ProjectDeletionService deletionService;
	private final JobService jobService;
	private final JdbcClient jdbc;
	private final ChangeLogService changeLog;

	ProjectDuplicateTransactions(ProjectRepository projectRepository, LayerRepository layerRepository,
			LayerFieldRepository fieldRepository, ProjectDeletionService deletionService, JobService jobService,
			JdbcClient jdbc, ChangeLogService changeLog) {
		this.projectRepository = projectRepository;
		this.layerRepository = layerRepository;
		this.fieldRepository = fieldRepository;
		this.deletionService = deletionService;
		this.jobService = jobService;
		this.jdbc = jdbc;
		this.changeLog = changeLog;
	}

	@Transactional
	Start start(UUID jobId, UUID sourceProjectId, String requestedName) {
		Project source = projectRepository.findById(sourceProjectId)
				.orElseThrow(() -> new NotFoundException("Projekt " + sourceProjectId + " existiert nicht"));
		String name = requestedName == null || requestedName.isBlank()
				? nextCopyName(source.getName()) : requestedName.trim();
		if (name.isEmpty()) {
			throw new IllegalArgumentException("Name darf nicht leer sein");
		}

		Project target = new Project(name, source.getDescription(), source.getSrid(), source.getBasemap());
		target.setBasemapOpacity(source.getBasemapOpacity());
		target.setCenter(source.getCenter());
		target.setZoom(source.getZoom());
		target.setExtent(source.getExtent());
		// view_state is deliberately NOT copied. It names layer ids of the source project,
		// and the target gets fresh ones, so a copied value would point at nothing and be
		// discarded the moment it was read anyway (ProjectService.viewState cleans up
		// entries for layers that do not exist). A new copy also should not inherit the
		// source's selection and filters -- it is meant to open clean.
		target = projectRepository.saveAndFlush(target);

		List<Layer> layers = layerRepository.findByProjectOrdered(sourceProjectId);
		long total = layers.stream().mapToLong(Layer::getFeatureCount).sum();
		jobService.markDuplicateRunning(jobId, target.getId(), total);
		return new Start(target.getId(), layers.stream().map(Layer::getId).toList(), total);
	}

	@Transactional
	void copyLayer(UUID jobId, UUID sourceLayerId, UUID targetProjectId, long totalFeatures) {
		Layer source = layerRepository.findById(sourceLayerId)
				.orElseThrow(() -> new IllegalStateException("Quelllayer ist nicht mehr vorhanden"));
		Project target = projectRepository.getReferenceById(targetProjectId);
		UUID targetLayerId = Uuid7.generate();

		// A map image (kind WMS) has no payload table -- it is copied as a catalog row
		// alone, its service binding carried straight across. Everything below the two
		// branches (attributes, feature_count rollup, progress) is identical either way.
		Layer copy = source.isVectorLayer()
				? copyVectorLayer(source, target, targetLayerId)
				: copyWmsLayer(source, target, targetLayerId);

		copy.setCopyMetadata(source.getFeatureCount(), source.isVisible(), source.getZIndex(),
				source.getMinZoom(), source.getMaxZoom(), source.getStyle(),
				source.getBasemap(), source.getBasemapOpacity(), source.getClipMode(), source.getExtent(),
				source.getProvenance());
		copy = layerRepository.save(copy);
		for (LayerField field : fieldRepository.findByLayerIdOrderByOrdinalAsc(sourceLayerId)) {
			fieldRepository.save(new LayerField(copy, field.getSourceName(), field.getColumnName(),
					field.getDataType(), field.getOrdinal()));
		}

		// Logged here rather than deferred: unlike an import, a failed duplicate's
		// compensateAndFail drops the whole target *project* (ON DELETE CASCADE also
		// takes every change_log row logged against it along), so nothing is left
		// orphaned if this never reaches complete(). No client name -- duplicating a
		// project carries none today (see ClientId).
		changeLog.record(targetProjectId, copy.getId(), copy.getName(), ChangeLogAction.LAYER_CREATE, null, 1, null);
		if (source.getFeatureCount() > 0) {
			long inserted = Math.min(source.getFeatureCount(), Integer.MAX_VALUE);
			changeLog.record(targetProjectId, copy.getId(), copy.getName(),
					ChangeLogAction.FEATURE_INSERT, null, (int) inserted, null);
		}

		long processed = jdbc.sql("""
				SELECT COALESCE(SUM(feature_count), 0)
				FROM gis_meta.layer WHERE project_id = :projectId
				""").param("projectId", targetProjectId).query(Long.class).single();
		jobService.updateProgress(jobId, processed, totalFeatures, 0);
	}

	/** Copies the payload table, its indexes and the catalog row -- the original behaviour, unchanged. */
	private Layer copyVectorLayer(Layer source, Project target, UUID targetLayerId) {
		String targetTable = SqlIdentifier.tableName(targetLayerId);

		// INCLUDING ALL would copy the source's index definitions and their schema-wide
		// names. Excluding indexes avoids that collision, at the price of dropping every
		// index rather than only the colliding ones -- so PK, GiST and the source's own
		// attribute indexes are all recreated below, under the copy's own names.
		jdbc.sql("CREATE TABLE " + SqlIdentifier.quoteLayerTable(targetTable) + " (LIKE "
				+ SqlIdentifier.quoteLayerTable(source.getTableName())
				+ " INCLUDING ALL EXCLUDING INDEXES)").update();
		jdbc.sql("INSERT INTO " + SqlIdentifier.quoteLayerTable(targetTable)
				+ " OVERRIDING SYSTEM VALUE SELECT * FROM "
				+ SqlIdentifier.quoteLayerTable(source.getTableName())).update();
		jdbc.sql("ALTER TABLE " + SqlIdentifier.quoteLayerTable(targetTable)
				+ " ADD PRIMARY KEY (fid)").update();
		jdbc.sql("CREATE INDEX " + SqlIdentifier.quoteColumn(targetTable + "_geom_idx") + " ON "
				+ SqlIdentifier.quoteLayerTable(targetTable) + " USING GIST (geom)").update();
		copyAttributeIndexes(source.getTableName(), targetTable);
		long nextFid = jdbc.sql("SELECT COALESCE(MAX(fid), 0) + 1 FROM "
				+ SqlIdentifier.quoteLayerTable(targetTable)).query(Long.class).single();
		jdbc.sql("ALTER TABLE " + SqlIdentifier.quoteLayerTable(targetTable)
				+ " ALTER COLUMN fid RESTART WITH " + nextFid).update();

		return new Layer(targetLayerId, target, source.getName(), targetTable,
				source.getGeometryType(), source.getSrid());
	}

	/** Nothing to copy but the catalog row -- a map image has no table, so no DDL runs at all. */
	private static Layer copyWmsLayer(Layer source, Project target, UUID targetLayerId) {
		return new Layer(targetLayerId, target, source.getName(), source.getWmsServiceUrl(),
				source.getWmsLayers(), source.getWmsImageFormat(), source.getWmsLegendUrl(),
				Boolean.TRUE.equals(source.getWmsQueryable()));
	}

	/**
	 * Recreates the source table's plain attribute indexes on the copy. {@code EXCLUDING
	 * INDEXES} above drops every index, not only the two names that would have collided, and
	 * the primary key and the GiST index are the only two put back explicitly -- so a layer
	 * imported from the Geoportal lost the index on the service's own feature id (decision
	 * E6), the one the later reconcile looks rows up by. Nothing reports that kind of loss:
	 * the copy answers every query it is asked, only slowly, and only once the table is large
	 * enough to notice.
	 *
	 * <p>Which columns to index is read off the source table rather than derived again from
	 * the layer's fields. {@code layer.source_feature_id_field} holds the service's technical
	 * name, and the column was named from it by {@link SqlIdentifier#toColumnName}, whose
	 * result also depends on the other columns present -- re-running that here would be a
	 * second, separate implementation of the same rule, free to disagree with the first. The
	 * source table already knows the answer.
	 *
	 * <p>Restricted to plain single-column indexes -- not unique, not the primary key, no
	 * expression, no {@code WHERE} predicate -- and skipping {@code geom}, whose GiST index
	 * is recreated above: that is exactly what {@code TableCreator.createAttributeIndex}
	 * produces. An index of any other shape is left alone rather than silently recreated as
	 * something weaker than it was.
	 */
	private void copyAttributeIndexes(String sourceTable, String targetTable) {
		List<String> columns = jdbc.sql("""
				SELECT a.attname
				FROM pg_index i
				JOIN pg_class c ON c.oid = i.indrelid
				JOIN pg_namespace n ON n.oid = c.relnamespace
				JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = i.indkey[0]
				WHERE n.nspname = 'gis_data'
				  AND c.relname = :table
				  AND NOT i.indisprimary
				  AND NOT i.indisunique
				  AND i.indnatts = 1
				  AND i.indexprs IS NULL
				  AND i.indpred IS NULL
				  AND a.attname <> 'geom'
				ORDER BY a.attname
				""").param("table", sourceTable).query(String.class).list();

		for (String column : columns) {
			jdbc.sql("CREATE INDEX " + SqlIdentifier.quoteColumn(attributeIndexName(targetTable, column))
					+ " ON " + SqlIdentifier.quoteLayerTable(targetTable)
					+ " (" + SqlIdentifier.quoteColumn(column) + ")").update();
		}
	}

	/** Same name and same truncation rule {@code TableCreator.createAttributeIndex} uses, so a
	 *  copy is indistinguishable from a freshly imported layer. */
	private static String attributeIndexName(String tableName, String columnName) {
		String suffix = "_idx";
		int budget = SqlIdentifier.MAX_LENGTH - tableName.length() - 1 - suffix.length();
		String columnPart = columnName.length() > budget ? columnName.substring(0, budget) : columnName;
		return tableName + "_" + columnPart + suffix;
	}

	@Transactional
	void complete(UUID jobId) {
		jobService.markSucceeded(jobId, null);
	}

	@Transactional
	void compensateAndFail(UUID jobId, UUID targetProjectId, String reason) {
		deletionService.deleteProject(targetProjectId);
		jobService.markFailed(jobId, reason);
	}

	private String nextCopyName(String sourceName) {
		String base = copyName(sourceName, 1);
		if (!projectRepository.existsByNameIgnoreCase(base)) {
			return base;
		}
		for (int copy = 2; ; copy++) {
			String candidate = copyName(sourceName, copy);
			if (!projectRepository.existsByNameIgnoreCase(candidate)) {
				return candidate;
			}
		}
	}

	static String copyName(String sourceName, int copy) {
		String suffix = copy == 1 ? " (Kopie)" : " (Kopie " + copy + ")";
		int sourceLimit = PROJECT_NAME_MAX_LENGTH - suffix.length();
		String sourcePart = sourceName.length() <= sourceLimit
				? sourceName : sourceName.substring(0, sourceLimit).stripTrailing();
		return sourcePart + suffix;
	}
}
