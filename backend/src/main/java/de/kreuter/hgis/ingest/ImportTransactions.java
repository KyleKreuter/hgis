package de.kreuter.hgis.ingest;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.common.ExtentCalculator;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.common.TableCreator;
import de.kreuter.hgis.common.TableCreator.CreatedLayer;
import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction boundaries of an import, kept in a component separate from
 * {@link ImportService} so that {@code @Transactional} actually applies: methods called
 * from inside the same class bypass Spring's proxy and would silently run without a
 * transaction. Called from {@code ImportService} through this bean's proxy instead, each
 * public method here is its own short transaction as required by the phase split:
 *
 * <ul>
 *   <li>{@link #begin} -- phase A: create the table, its index and catalog rows, and
 *       move the job to RUNNING, all atomically.</li>
 *   <li>{@link #writeBatch} -- phase B, called once per batch: write up to
 *       {@link FeatureWriter#BATCH_SIZE} rows and update the job's progress together.</li>
 *   <li>{@link #complete} -- phase C: compute feature_count and the EPSG:4326 extent,
 *       bump data_version once more and move the job to SUCCEEDED.</li>
 *   <li>{@link #compensateAndFail} -- the failure path for anything that goes wrong
 *       after phase A already committed: drop the table, delete the catalog rows, mark
 *       the job FAILED.</li>
 * </ul>
 */
@Component
class ImportTransactions {

	private final TableCreator tableCreator;
	private final FeatureWriter featureWriter;
	private final JobService jobService;
	private final LayerRepository layerRepository;
	private final JdbcClient jdbc;
	private final ExtentCalculator extentCalculator;

	ImportTransactions(TableCreator tableCreator, FeatureWriter featureWriter, JobService jobService,
			LayerRepository layerRepository, JdbcClient jdbc, ExtentCalculator extentCalculator) {
		this.tableCreator = tableCreator;
		this.featureWriter = featureWriter;
		this.jobService = jobService;
		this.layerRepository = layerRepository;
		this.jdbc = jdbc;
		this.extentCalculator = extentCalculator;
	}

	@Transactional
	CreatedLayer begin(Project project, UUID jobId, SourceSchema schema, String layerName) {
		CreatedLayer created = tableCreator.createLayerTable(project, schema, layerName);
		jobService.markRunning(jobId, created.layer().getId(), schema.featureCount());
		return created;
	}

	/** @return total rows written so far, {@code processedBefore} plus this batch */
	@Transactional
	long writeBatch(CreatedLayer created, int sourceSrid, int targetSrid, List<SourceFeature> batch,
			UUID jobId, long processedBefore, Long totalCount) {
		int written = featureWriter.writeBatch(
				created.layer().getTableName(), created.columns(), sourceSrid, targetSrid, batch);
		long processedAfter = processedBefore + written;

		// A write to the payload table, so the tile cache buster moves -- see contract
		// section 5.5. skippedCount is deliberately not reported yet: the reader's count
		// is only authoritative once the whole stream has been consumed.
		layerRepository.bumpDataVersion(created.layer().getId());
		jobService.updateProgress(jobId, processedAfter, totalCount, 0);

		return processedAfter;
	}

	@Transactional
	void complete(UUID jobId, UUID layerId, int srid, long featureCount, long skippedCount) {
		Layer layer = layerRepository.findById(layerId)
				.orElseThrow(() -> new IllegalStateException("Layer " + layerId + " verschwand während des Imports"));

		layer.setFeatureCount(featureCount);
		layer.setExtent(extentCalculator.forLayer(layer.getTableName(), srid));

		// The rollup below reads the layer extent straight from the database, and the
		// assignment above is still sitting in the persistence context. Without this
		// flush it would read NULL and the project extent would stay empty.
		layerRepository.flush();

		// Roll the layer extents up to the project. The map uses it to pick its opening
		// view for a project that has never been opened, so without this a freshly
		// imported project would start zoomed out over the whole country.
		updateProjectExtent(layer.getProject().getId());

		String message = skippedCount > 0
				? skippedCount + " von " + (featureCount + skippedCount)
						+ " Datensätzen übersprungen (fehlende oder unlesbare Geometrie)"
				: null;
		jobService.markSucceeded(jobId, message);
	}

	@Transactional
	void compensateAndFail(UUID jobId, UUID layerId, String tableName, String reason) {
		tableCreator.dropLayer(layerId, tableName);
		jobService.markFailed(jobId, reason);
	}

	/** Job never even got as far as creating a table -- nothing to compensate, just record why. */
	@Transactional
	void failBeforeTableExists(UUID jobId, String reason) {
		jobService.markFailed(jobId, reason);
	}

	/**
	 * Recomputes a project's extent as the union of its layer extents.
	 *
	 * Both are already EPSG:4326, so no transform is needed. Runs as plain SQL because
	 * the alternative -- loading every layer entity just to union boxes -- would read far
	 * more than it needs to.
	 */
	private void updateProjectExtent(UUID projectId) {
		jdbc.sql("""
				UPDATE gis_meta.project p
				SET extent = sub.box
				FROM (
				  SELECT ST_Envelope(ST_Extent(extent)::geometry) AS box
				  FROM gis_meta.layer
				  WHERE project_id = :projectId AND extent IS NOT NULL
				) sub
				WHERE p.id = :projectId AND sub.box IS NOT NULL
				""")
				.param("projectId", projectId)
				.update();
	}

	/**
	 * Bounding box of every feature in the table, transformed to EPSG:4326 for storage as
	 * layer metadata. {@code ST_Extent} yields a box2d with no CRS of its own, hence the
	 * explicit {@code ST_SetSRID} before the transform. Read back as WKB and parsed with
	 * JTS rather than relying on driver-level geometry support, which raw JDBC queries
	 * (unlike Hibernate's spatial dialect) don't have.
	 */
}
