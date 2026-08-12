package de.kreuter.hgis.ingest;

import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.TableCreator.CreatedLayer;
import de.kreuter.hgis.ingest.spi.SourceFeature;
import de.kreuter.hgis.ingest.spi.SourceReader;
import de.kreuter.hgis.ingest.spi.SourceSchema;
import de.kreuter.hgis.jobs.AsyncConfig;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one import from an already-opened {@link SourceReader} into a new layer.
 *
 * Runs as three transactions rather than one, on purpose: a multi-gigabyte shapefile can
 * take minutes to stream, and holding a single database transaction open for that long
 * would pin a connection, bloat WAL and block autovacuum on the target table for no
 * benefit -- nothing before the last row is written can usefully be rolled back anyway,
 * since a half written table gets dropped wholesale on failure rather than rolled back
 * row by row.
 *
 * <p>Expected calling contract for whoever wires the multipart upload endpoint: create
 * the job via {@code JobService.create(...)} while still in PENDING, respond 202 with
 * it, then hand the job id and an already-constructed {@link SourceReader} for the
 * uploaded file to {@link #runImportAsync}. This class never creates the job itself and
 * never picks a reader implementation -- both are the caller's responsibility, so this
 * code has no dependency on file formats or multipart handling.
 */
@Service
public class ImportService {

	private static final Logger log = LoggerFactory.getLogger(ImportService.class);

	/** Above this share of skipped features, the import is treated as failed outright. */
	static final double MAX_SKIP_RATIO = 0.05;

	private final ImportTransactions transactions;
	private final ProjectRepository projectRepository;

	ImportService(ImportTransactions transactions, ProjectRepository projectRepository) {
		this.transactions = transactions;
		this.projectRepository = projectRepository;
	}

	/**
	 * Production entry point: dispatches onto the dedicated import executor so the
	 * calling thread returns immediately. Delegates to {@link #runImport}, which stays
	 * synchronous so it can be exercised directly in tests without an async harness.
	 */
	@Async(AsyncConfig.IMPORT_EXECUTOR)
	public void runImportAsync(UUID jobId, UUID projectId, SourceReader reader, String layerName,
			Integer sourceSridOverride) {
		runImport(jobId, projectId, reader, layerName, sourceSridOverride);
	}

	/**
	 * The three-phase import described on the class. Never throws: every failure path
	 * ends in the job being marked FAILED with a readable message, because nothing is
	 * left to report a failure to once this method is running on a background thread.
	 *
	 * <p>Also always closes {@code reader} exactly once, on every path -- including the
	 * three below that stop before phase B's own try-with-resources ever gets to it. For a
	 * Shapefile, {@code reader} is the only thing holding the path to the directory its ZIP
	 * was already extracted into; losing that path here would leak it for good, since
	 * nothing else in the application ever revisits it.
	 */
	public void runImport(UUID jobId, UUID projectId, SourceReader reader, String layerName,
			Integer sourceSridOverride) {
		Project project = projectRepository.findById(projectId).orElse(null);
		if (project == null) {
			transactions.failBeforeTableExists(jobId, "Projekt " + projectId + " existiert nicht");
			closeQuietly(reader, jobId);
			return;
		}

		SourceSchema schema;
		try {
			schema = reader.schema();
		} catch (Exception e) {
			log.error("Import {} failed while inspecting the source schema", jobId, e);
			transactions.failBeforeTableExists(jobId, "Der Import kann die Quelldatei nicht lesen: " + describe(e));
			closeQuietly(reader, jobId);
			return;
		}

		CreatedLayer created;
		try {
			// Phase A. DDL is transactional in PostgreSQL, so a failure anywhere in here
			// rolls back the table together with the catalog rows -- there is nothing to
			// compensate, unlike every later phase.
			created = transactions.begin(project, jobId, schema, layerName);
		} catch (Exception e) {
			log.error("Import {} failed before its table existed", jobId, e);
			transactions.failBeforeTableExists(jobId, "Der Import kann den Layer nicht anlegen: " + describe(e));
			closeQuietly(reader, jobId);
			return;
		}

		int sourceSrid = sourceSridOverride != null ? sourceSridOverride : schema.sourceSrid();
		int targetSrid = project.getSrid();
		Layer layer = created.layer();

		try {
			long processed;
			// The reader's own resources are scoped strictly to phase B: if closing them
			// fails after all batches were written, phase C below must never run, or a
			// close() failure could downgrade a job that already succeeded back to FAILED.
			try (reader; Stream<SourceFeature> features = reader.features()) {
				processed = streamAndWrite(features, created, sourceSrid, targetSrid, jobId, schema.featureCount());
			}

			long skipped = reader.skippedCount();
			requireAcceptableSkipRatio(processed, skipped);

			// Phase C.
			transactions.complete(jobId, layer.getId(), targetSrid, processed, skipped);
		} catch (Exception e) {
			log.error("Import {} failed, compensating", jobId, e);
			transactions.compensateAndFail(jobId, layer.getId(), layer.getTableName(), describe(e));
		}
	}

	/** Phase B: streams features in batches of {@link FeatureWriter#BATCH_SIZE}, each its
	 *  own short transaction with a progress update bundled in. */
	private long streamAndWrite(Stream<SourceFeature> features, CreatedLayer created, int sourceSrid,
			int targetSrid, UUID jobId, Long totalCount) {
		long processed = 0;
		List<SourceFeature> batch = new ArrayList<>(FeatureWriter.BATCH_SIZE);

		Iterator<SourceFeature> it = features.iterator();
		while (it.hasNext()) {
			batch.add(it.next());
			if (batch.size() == FeatureWriter.BATCH_SIZE) {
				processed = transactions.writeBatch(created, sourceSrid, targetSrid, batch, jobId, processed,
						totalCount);
				batch.clear();
			}
		}
		if (!batch.isEmpty()) {
			processed = transactions.writeBatch(created, sourceSrid, targetSrid, batch, jobId, processed,
					totalCount);
		}
		return processed;
	}

	private static void requireAcceptableSkipRatio(long processed, long skipped) {
		long total = processed + skipped;
		double skipRatio = total == 0 ? 0.0 : (double) skipped / total;
		if (skipRatio > MAX_SKIP_RATIO) {
			throw new ImportFailedException(
					"Der Import hat %.1f%% der Objekte übersprungen (Grenzwert 5%%). Er ist abgebrochen."
							.formatted(skipRatio * 100));
		}
	}

	private static String describe(Exception e) {
		String message = e.getMessage();
		return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
	}

	/**
	 * Closes {@code reader} on a path that stops before phase B's try-with-resources would
	 * have done it. A failure here is logged and swallowed, never rethrown: the job has
	 * already been given its failure reason by the caller, and a problem releasing a
	 * resource that failure never got to use must not overwrite that reason.
	 */
	private static void closeQuietly(SourceReader reader, UUID jobId) {
		try {
			reader.close();
		} catch (Exception e) {
			log.warn("Import {} could not close its source reader after an early failure", jobId, e);
		}
	}
}
