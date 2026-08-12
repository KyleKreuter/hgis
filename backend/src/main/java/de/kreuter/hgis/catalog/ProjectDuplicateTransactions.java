package de.kreuter.hgis.catalog;

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

	ProjectDuplicateTransactions(ProjectRepository projectRepository, LayerRepository layerRepository,
			LayerFieldRepository fieldRepository, ProjectDeletionService deletionService, JobService jobService,
			JdbcClient jdbc) {
		this.projectRepository = projectRepository;
		this.layerRepository = layerRepository;
		this.fieldRepository = fieldRepository;
		this.deletionService = deletionService;
		this.jobService = jobService;
		this.jdbc = jdbc;
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
		String targetTable = SqlIdentifier.tableName(targetLayerId);

		// INCLUDING ALL would copy the source's index definitions and their schema-wide
		// names. Excluding indexes avoids that collision; PK and GiST are recreated below.
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
		long nextFid = jdbc.sql("SELECT COALESCE(MAX(fid), 0) + 1 FROM "
				+ SqlIdentifier.quoteLayerTable(targetTable)).query(Long.class).single();
		jdbc.sql("ALTER TABLE " + SqlIdentifier.quoteLayerTable(targetTable)
				+ " ALTER COLUMN fid RESTART WITH " + nextFid).update();

		Layer copy = new Layer(targetLayerId, target, source.getName(), targetTable,
				source.getGeometryType(), source.getSrid());
		copy.setCopyMetadata(source.getFeatureCount(), source.isVisible(), source.getZIndex(),
				source.getMinZoom(), source.getMaxZoom(), source.getStyle(),
				source.getBasemap(), source.getBasemapOpacity(), source.getExtent());
		copy = layerRepository.save(copy);
		for (LayerField field : fieldRepository.findByLayerIdOrderByOrdinalAsc(sourceLayerId)) {
			fieldRepository.save(new LayerField(copy, field.getSourceName(), field.getColumnName(),
					field.getDataType(), field.getOrdinal()));
		}
		long processed = jdbc.sql("""
				SELECT COALESCE(SUM(feature_count), 0)
				FROM gis_meta.layer WHERE project_id = :projectId
				""").param("projectId", targetProjectId).query(Long.class).single();
		jobService.updateProgress(jobId, processed, totalFeatures, 0);
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
