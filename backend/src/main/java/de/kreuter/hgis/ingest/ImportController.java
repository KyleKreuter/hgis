package de.kreuter.hgis.ingest;

import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.ingest.reader.SourceReaderFactory;
import de.kreuter.hgis.ingest.spi.SourceReader;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import de.kreuter.hgis.jobs.dto.JobDtos;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Entry point of the import chain: this is where the reading side (track A) and the
 * writing side (track B) meet.
 *
 * The upload is stored, a reader is opened for it and its schema inspected -- all
 * synchronously, so format errors, an unreadable file or an implausible CRS come back
 * as a proper 400 instead of a job that fails seconds later. Only the actual writing
 * runs asynchronously.
 */
@RestController
public class ImportController {

	private static final Logger log = LoggerFactory.getLogger(ImportController.class);

	private final ProjectRepository projectRepository;
	private final JobService jobService;
	private final ImportService importService;
	private final UploadStorage uploadStorage;

	ImportController(ProjectRepository projectRepository, JobService jobService,
			ImportService importService, UploadStorage uploadStorage) {
		this.projectRepository = projectRepository;
		this.jobService = jobService;
		this.importService = importService;
		this.uploadStorage = uploadStorage;
	}

	@PostMapping("/api/projects/{projectId}/imports")
	public ResponseEntity<JobDtos.Response> startImport(
			@PathVariable UUID projectId,
			@RequestParam("file") MultipartFile file,
			@RequestParam(required = false) String name,
			@RequestParam(required = false) Integer srid,
			@RequestParam(required = false) String charset) {

		if (!projectRepository.existsById(projectId)) {
			throw new NotFoundException("Projekt " + projectId + " existiert nicht");
		}
		if (file.isEmpty()) {
			throw new BadRequestException("Die hochgeladene Datei ist leer");
		}

		String filename = file.getOriginalFilename();
		Job job = jobService.create(projectId, Job.Type.IMPORT, filename);
		Path uploaded = uploadStorage.store(file, job.getId());

		String layerName = (name == null || name.isBlank())
				? UploadStorage.baseNameOf(filename)
				: name.trim();

		SourceReader reader;
		try {
			// Opening reads the schema, which is where a broken file, an unknown format
			// or an implausible CRS surfaces. Doing it here keeps those failures in the
			// HTTP response rather than burying them in a job that fails later.
			reader = SourceReaderFactory.open(uploaded, srid, parseCharset(charset));
		}
		catch (BadRequestException ex) {
			uploadStorage.cleanUp(uploaded);
			jobService.markFailed(job.getId(), ex.getMessage());
			throw ex;
		}
		catch (RuntimeException ex) {
			uploadStorage.cleanUp(uploaded);
			jobService.markFailed(job.getId(), ex.getMessage());
			log.warn("Could not open uploaded file {} for job {}", filename, job.getId(), ex);
			throw new BadRequestException("Die Datei konnte nicht gelesen werden: " + ex.getMessage());
		}

		importService.runImportAsync(job.getId(), projectId, reader, layerName, srid);

		return ResponseEntity.accepted().body(jobService.get(job.getId()));
	}

	private static Charset parseCharset(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		try {
			return Charset.forName(name.trim());
		}
		catch (IllegalCharsetNameException | UnsupportedCharsetException ex) {
			throw new BadRequestException("Unbekannte Zeichenkodierung: " + name);
		}
	}
}
