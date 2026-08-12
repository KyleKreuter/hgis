package de.kreuter.hgis.geoportal;

import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.LayerProvenance;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.geoportal.dto.GeoportalDtos;
import de.kreuter.hgis.ingest.ImportService;
import de.kreuter.hgis.ingest.reader.OgcFeaturesSourceReader;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import de.kreuter.hgis.jobs.dto.JobDtos;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * CONTRACT.md 11.6. Deliberately its own endpoint rather than an extension of {@code
 * ingest.ImportController}: that controller requires either a file or an {@code uploadId}
 * and owns upload storage and its janitor, none of which a network fetch has any use for.
 * What it shares with the file import is everything downstream of the reader --
 * {@link ImportService#runImportAsync}, the same {@code Job}, the same polling endpoint --
 * so a dataset picked here behaves exactly like a file the user just uploaded, one screen
 * later.
 */
@RestController
class GeoportalImportController {

	private final ProjectRepository projectRepository;
	private final JobService jobService;
	private final ImportService importService;
	private final GeoportalDatasetService datasetService;
	private final RestClient geoportalRestClient;

	GeoportalImportController(ProjectRepository projectRepository, JobService jobService, ImportService importService,
			GeoportalDatasetService datasetService, RestClient geoportalRestClient) {
		this.projectRepository = projectRepository;
		this.jobService = jobService;
		this.importService = importService;
		this.datasetService = datasetService;
		this.geoportalRestClient = geoportalRestClient;
	}

	@PostMapping("/api/projects/{projectId}/geoportal-imports")
	public ResponseEntity<JobDtos.Response> startImport(@PathVariable UUID projectId,
			@Valid @RequestBody GeoportalDtos.ImportRequest request) {
		requireProject(projectId);
		requireValidBbox(request.bbox());
		// A dataset without an OGC API Features binding (WMS-only, or WFS-only in this
		// stage) is rejected here, before a job exists for it -- the same reasoning
		// ImportController applies to resolveUpload: nothing storable, nothing to poll.
		GeoportalCatalogEntry entry = datasetService.requireImportable(request.datasetId());

		String layerName = (request.name() == null || request.name().isBlank())
				? entry.title()
				: request.name().trim();

		Job job = jobService.create(projectId, Job.Type.IMPORT, layerName);

		OgcFeaturesSourceReader reader;
		try {
			// Opening the reader runs its own schema fetch (collection info, queryables,
			// the first page), which is where an unknown field name or an unreachable
			// service surfaces -- doing that here keeps the failure in the HTTP response
			// rather than burying it in a job that fails moments later.
			reader = new OgcFeaturesSourceReader(geoportalRestClient, entry.apiUrl(), entry.collection(),
					request.bbox(), request.fields(), entry.gfiAttributes());
		}
		catch (BadRequestException ex) {
			jobService.markFailed(job.getId(), ex.getMessage());
			throw ex;
		}
		catch (RuntimeException ex) {
			String message = "Der Import kann den Geoportal-Datensatz nicht lesen: " + describe(ex);
			jobService.markFailed(job.getId(), message);
			throw new BadRequestException(message);
		}

		// CONTRACT.md 11.7: recorded once, up front, rather than re-derived when the layer
		// is later shown -- the sourceFeatureIdField the reader resolved from queryables()
		// is exactly what E6's future reconcile needs, and re-fetching queryables just to
		// read it again on every layer-properties request would be paying for the same
		// answer twice.
		LayerProvenance provenance = new LayerProvenance(
				entry.attribution(), GeoportalLicense.NAME, GeoportalLicense.URL,
				entry.datasetUri(), entry.metadataUrl(), entry.id(),
				reader.idFieldIndex() >= 0 ? reader.columnNameBasis().get(reader.idFieldIndex()) : null,
				Instant.now());

		// No SRID override (CONTRACT.md 11.8): the reader reports what it read, PostGIS
		// transforms it. columnNameBasis and idFieldIndex carry decisions E1 and E6 down
		// to TableCreator -- see OgcFeaturesSourceReader for what they mean.
		importService.runImportAsync(job.getId(), projectId, reader, layerName, null,
				reader.columnNameBasis(), reader.idFieldIndex(), provenance);

		return ResponseEntity.accepted().body(jobService.get(job.getId()));
	}

	private void requireProject(UUID projectId) {
		if (!projectRepository.existsById(projectId)) {
			throw new NotFoundException("Projekt " + projectId + " existiert nicht");
		}
	}

	private static void requireValidBbox(double[] bbox) {
		if (bbox != null && bbox.length != 4) {
			throw new BadRequestException("bbox muss vier Zahlen enthalten: minLng,minLat,maxLng,maxLat");
		}
	}

	private static String describe(RuntimeException e) {
		String message = e.getMessage();
		return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
	}
}
