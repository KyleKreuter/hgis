package de.kreuter.hgis.catalog;

import de.kreuter.hgis.catalog.dto.ProjectDtos;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.GeometryConfig;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import de.kreuter.hgis.jobs.dto.JobDtos;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

	/** Fallback storage CRS: UTM zone 32N, metric and standard for Germany. */
	public static final int DEFAULT_SRID = 25832;

	private final ProjectRepository repository;
	private final ProjectDeletionService deletionService;
	private final GeometryFactory geometryFactory;
	private final JdbcClient jdbc;
	private final JobService jobService;
	private final ProjectDuplicateService duplicateService;

	ProjectService(ProjectRepository repository, ProjectDeletionService deletionService,
			GeometryFactory geometryFactory, JdbcClient jdbc, JobService jobService,
			ProjectDuplicateService duplicateService) {
		this.repository = repository;
		this.deletionService = deletionService;
		this.geometryFactory = geometryFactory;
		this.jdbc = jdbc;
		this.jobService = jobService;
		this.duplicateService = duplicateService;
	}

	@Transactional(readOnly = true)
	public List<ProjectDtos.Summary> list() {
		return repository.findAllSummaries().stream()
				.map(row -> new ProjectDtos.Summary(
						row.getId(), row.getName(), row.getDescription(), row.getSrid(),
						row.getLayerCount(), row.getFeatureCount(),
						row.getLastOpenedAt(), row.getCreatedAt()))
				.toList();
	}

	@Transactional
	public ProjectDtos.Detail create(ProjectDtos.CreateRequest request) {
		int srid = request.srid() == null ? DEFAULT_SRID : request.srid();
		requireKnownSrid(srid);

		Project project = new Project(
				request.name().trim(),
				trimToNull(request.description()),
				srid,
				request.basemap());

		// saveAndFlush, not save: Hibernate assigns @CreationTimestamp during flush, and
		// the response is built here rather than after commit. Without the flush the
		// client would receive null timestamps for a row that does have them.
		return toDetail(repository.saveAndFlush(project), 0, 0);
	}

	/**
	 * Loads a project. When {@code markOpened} is set, last_opened_at is refreshed --
	 * that timestamp drives the ordering of the project browser.
	 */
	@Transactional
	public ProjectDtos.Detail open(UUID id) {
		Project project = require(id);
		project.setLastOpenedAt(Instant.now());
		return withCounts(project);
	}

	@Transactional(readOnly = true)
	public ProjectDtos.Detail get(UUID id) {
		return withCounts(require(id));
	}

	@Transactional
	public ProjectDtos.Detail update(UUID id, ProjectDtos.UpdateRequest request) {
		Project project = require(id);

		if (request.name() != null) {
			String name = request.name().trim();
			if (name.isEmpty()) {
				throw new BadRequestException("Name darf nicht leer sein");
			}
			project.setName(name);
		}
		if (request.description() != null) {
			project.setDescription(trimToNull(request.description()));
		}
		if (request.basemap() != null) {
			project.setBasemap(request.basemap());
		}
		if (request.center() != null) {
			project.setCenter(toPoint(request.center()));
		}
		if (request.zoom() != null) {
			project.setZoom(request.zoom());
		}

		return withCounts(project);
	}

	@Transactional(readOnly = true)
	public ProjectDtos.DeletionImpact deletionImpact(UUID id) {
		require(id);
		ProjectCountsRow counts = repository.countsFor(id);
		return new ProjectDtos.DeletionImpact(counts.getLayerCount(), counts.getFeatureCount());
	}

	@Transactional
	public void delete(UUID id) {
		require(id);
		deletionService.deleteProject(id);
	}

	public JobDtos.Response duplicate(UUID id, ProjectDtos.DuplicateRequest request) {
		require(id);
		if (request.name() != null && request.name().trim().isEmpty()) {
			throw new BadRequestException("Name darf nicht leer sein");
		}
		Job job = jobService.create(id, Job.Type.DUPLICATE, null);
		duplicateService.runDuplicateAsync(job.getId(), id, request.name());
		return jobService.get(job.getId());
	}

	// --- helpers ---------------------------------------------------------------

	private Project require(UUID id) {
		return repository.findById(id)
				.orElseThrow(() -> new NotFoundException("Projekt " + id + " existiert nicht"));
	}

	/**
	 * Validates against spatial_ref_sys instead of a hard coded whitelist, so every CRS
	 * that PROJ knows is accepted -- and a typo is rejected at creation time rather than
	 * surfacing much later during the first import.
	 */
	private void requireKnownSrid(int srid) {
		boolean known = jdbc.sql("SELECT EXISTS(SELECT 1 FROM spatial_ref_sys WHERE srid = :srid)")
				.param("srid", srid)
				.query(Boolean.class)
				.single();
		if (!known) {
			throw new BadRequestException(
					"EPSG:" + srid + " ist der Datenbank nicht bekannt und kann nicht als Projekt-CRS dienen");
		}
	}

	/**
	 * Flushes pending changes before reading, so updated_at and last_opened_at in the
	 * response reflect what the database will actually hold after commit.
	 */
	private ProjectDtos.Detail withCounts(Project project) {
		repository.flush();
		ProjectCountsRow counts = repository.countsFor(project.getId());
		return toDetail(project, counts.getLayerCount(), counts.getFeatureCount());
	}

	private ProjectDtos.Detail toDetail(Project p, long layerCount, long featureCount) {
		return new ProjectDtos.Detail(
				p.getId(), p.getName(), p.getDescription(), p.getSrid(), p.getBasemap(),
				toLngLat(p.getCenter()), p.getZoom(), toBbox(p.getExtent()),
				layerCount, featureCount,
				p.getLastOpenedAt(), p.getCreatedAt(), p.getUpdatedAt());
	}

	private Point toPoint(double[] lngLat) {
		if (lngLat.length != 2) {
			throw new BadRequestException("center muss genau zwei Werte enthalten: [lng, lat]");
		}
		double lng = lngLat[0];
		double lat = lngLat[1];
		if (lng < -180 || lng > 180 || lat < -90 || lat > 90) {
			throw new BadRequestException(
					"center liegt ausserhalb des gueltigen Bereichs fuer EPSG:4326");
		}
		Point point = geometryFactory.createPoint(new Coordinate(lng, lat));
		point.setSRID(GeometryConfig.WGS84);
		return point;
	}

	private static double[] toLngLat(Point point) {
		return point == null ? null : new double[] { point.getX(), point.getY() };
	}

	private static double[] toBbox(Polygon polygon) {
		if (polygon == null) {
			return null;
		}
		Envelope e = polygon.getEnvelopeInternal();
		return new double[] { e.getMinX(), e.getMinY(), e.getMaxX(), e.getMaxY() };
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
