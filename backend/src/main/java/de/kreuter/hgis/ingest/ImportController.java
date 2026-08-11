package de.kreuter.hgis.ingest;

import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.ingest.UploadStorage.StoredUpload;
import de.kreuter.hgis.ingest.dto.InspectionDtos;
import de.kreuter.hgis.ingest.spi.SourceReader;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import de.kreuter.hgis.jobs.dto.JobDtos;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.UUID;
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
 *
 * <p>Both endpoints take either a file or the id of one already uploaded. That is what
 * makes the preview affordable: the dialog inspects, the user corrects the encoding, and
 * the second inspection re-reads a file that is already on the server rather than asking
 * for it again.
 */
@RestController
public class ImportController {

	private final ProjectRepository projectRepository;
	private final JobService jobService;
	private final ImportService importService;
	private final InspectionService inspectionService;
	private final UploadStorage uploadStorage;

	ImportController(ProjectRepository projectRepository, JobService jobService,
			ImportService importService, InspectionService inspectionService, UploadStorage uploadStorage) {
		this.projectRepository = projectRepository;
		this.jobService = jobService;
		this.importService = importService;
		this.inspectionService = inspectionService;
		this.uploadStorage = uploadStorage;
	}

	/**
	 * Reports what an import would produce, without producing anything: no job, no table,
	 * no catalog entry. The upload stays behind under the returned id, ready for the next
	 * inspection or for the import itself.
	 */
	@PostMapping("/api/projects/{projectId}/imports/inspect")
	public InspectionDtos.Response inspect(
			@PathVariable UUID projectId,
			@RequestParam(required = false) MultipartFile file,
			@RequestParam(required = false) UUID uploadId,
			@RequestParam(required = false) Integer srid,
			@RequestParam(required = false) String charset) {

		requireProject(projectId);
		StoredUpload upload = resolveUpload(file, uploadId);

		// A failed inspection deliberately leaves the file alone: the most likely next step
		// is the same file with a corrected encoding or CRS, and deleting it here would make
		// every correction cost another upload.
		return inspectionService.inspect(upload, srid, parseCharset(charset));
	}

	@PostMapping("/api/projects/{projectId}/imports")
	public ResponseEntity<JobDtos.Response> startImport(
			@PathVariable UUID projectId,
			@RequestParam(required = false) MultipartFile file,
			@RequestParam(required = false) UUID uploadId,
			@RequestParam(required = false) String name,
			@RequestParam(required = false) Integer srid,
			@RequestParam(required = false) String charset) {

		requireProject(projectId);
		StoredUpload upload = resolveUpload(file, uploadId);

		// The job is created only once the upload is known to be storable: an upload
		// rejected for its format would otherwise leave a PENDING job nobody ever finishes.
		Job job = jobService.create(projectId, Job.Type.IMPORT, upload.originalFilename());

		String layerName = (name == null || name.isBlank())
				? UploadStorage.baseNameOf(upload.originalFilename())
				: name.trim();

		SourceReader reader;
		try {
			// Opening reads the schema, which is where a broken file, an unknown format
			// or an implausible CRS surfaces. Doing it here keeps those failures in the
			// HTTP response rather than burying them in a job that fails later.
			reader = inspectionService.open(upload, srid, parseCharset(charset));
		}
		catch (BadRequestException ex) {
			// The upload survives, so the user can fix the encoding and import the same
			// file again; the janitor takes it if they never do.
			jobService.markFailed(job.getId(), ex.getMessage());
			throw ex;
		}

		importService.runImportAsync(job.getId(), projectId,
				new UploadConsumingReader(reader, uploadStorage, upload.file()), layerName, srid);

		return ResponseEntity.accepted().body(jobService.get(job.getId()));
	}

	private void requireProject(UUID projectId) {
		if (!projectRepository.existsById(projectId)) {
			throw new NotFoundException("Projekt " + projectId + " existiert nicht");
		}
	}

	/** Exactly one of the two: a new file to store, or the id of one already stored. */
	private StoredUpload resolveUpload(MultipartFile file, UUID uploadId) {
		boolean hasFile = file != null && !file.isEmpty();
		if (hasFile && uploadId != null) {
			throw new BadRequestException("Bitte entweder eine Datei oder eine uploadId senden, nicht beides");
		}
		if (hasFile) {
			return uploadStorage.store(file);
		}
		if (uploadId != null) {
			return uploadStorage.find(uploadId);
		}
		// An empty file part is worth its own message: it is the one case where the client
		// did send something and would otherwise be told it sent nothing.
		if (file != null) {
			throw new BadRequestException("Die hochgeladene Datei ist leer");
		}
		throw new BadRequestException("Es fehlt die hochzuladende Datei oder eine uploadId");
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
