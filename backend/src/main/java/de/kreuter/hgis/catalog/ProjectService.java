package de.kreuter.hgis.catalog;

import de.kreuter.hgis.catalog.dto.ProjectDtos;
import de.kreuter.hgis.basemap.BasemapCatalog;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.FieldValidationException;
import de.kreuter.hgis.common.GeometryConfig;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.jobs.Job;
import de.kreuter.hgis.jobs.JobService;
import de.kreuter.hgis.jobs.dto.JobDtos;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * <p>Deliberately outside the scope of {@code CatalogChanged} (plan "Der Live-Kanal
 * meldet auch Datenaenderungen"), unlike almost every write path in {@code
 * de.kreuter.hgis.catalog}'s other services: {@link #create}, {@link #update} and {@link
 * #delete} never touch {@code layer} or {@code layer_field}, so the trigger that drives
 * {@code catalog_version} (V14__catalog_version.sql) never fires for them, and the event
 * documents its receiver's reaction as "reread {@code GET .../layers}" -- a project's own
 * name, description, basemap and last viewport are simply not in that response. Publishing
 * the event anyway would either be a lie (nothing to reread that actually reflects the
 * change) or would silently widen what the event means for {@code de.kreuter.hgis.events}'
 * documented contract, mid-plan, while the frontend package is being built against it in
 * parallel. {@code duplicate}'s own target-project announcement is the one exception, and
 * it is made by {@link ProjectDuplicateTransactions#complete}, which knows when the copy is
 * actually finished -- not by this class, which only starts the job.
 *
 * <p>{@link #update} does publish an event of its own, though, for exactly two of its
 * fields: see {@link ProjectViewportChanged} for why {@code center} and {@code zoom} earn
 * the exception that {@code name}, {@code description}, {@code basemap} and {@code
 * basemapOpacity} deliberately do not get.
 */
@Service
public class ProjectService {

	private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

	/** Fallback storage CRS: UTM zone 32N, metric and standard for Germany. */
	public static final int DEFAULT_SRID = 25832;

	/** Far above any plausible on-screen selection; see CONTRACT.md phase 17. */
	private static final int MAX_SELECTION_PER_LAYER = 10_000;
	private static final int MAX_QUERY_TEXT_LENGTH = 2000;

	/** Bounds for {@code limit} on the project browser; see CONTRACT.md phase 22. */
	private static final int MIN_PAGE_SIZE = 1;
	private static final int MAX_PAGE_SIZE = 100;

	/**
	 * Guards {@link #update}'s viewport comparison against float noise -- a value that
	 * round-tripped through JSON and back through JTS must not read as a change just
	 * because the last bit or two differs from what was stored. Nowhere near a screen
	 * pixel at any zoom a human could reach, and far below a degree of longitude/latitude
	 * that matters, so nothing genuine is ever hidden behind it.
	 */
	private static final double VIEWPORT_EPSILON = 1e-9;

	private final ProjectRepository repository;
	private final ProjectDeletionService deletionService;
	private final GeometryFactory geometryFactory;
	private final JdbcClient jdbc;
	private final JobService jobService;
	private final ProjectDuplicateService duplicateService;
	private final LayerRepository layerRepository;
	private final ObjectMapper objectMapper;
	/** Where {@link ProjectViewStateChanged} goes; who listens is not this package's business. */
	private final ApplicationEventPublisher events;

	ProjectService(ProjectRepository repository, ProjectDeletionService deletionService,
			GeometryFactory geometryFactory, JdbcClient jdbc, JobService jobService,
			ProjectDuplicateService duplicateService, LayerRepository layerRepository,
			ObjectMapper objectMapper, ApplicationEventPublisher events) {
		this.repository = repository;
		this.deletionService = deletionService;
		this.geometryFactory = geometryFactory;
		this.jdbc = jdbc;
		this.jobService = jobService;
		this.duplicateService = duplicateService;
		this.layerRepository = layerRepository;
		this.objectMapper = objectMapper;
		this.events = events;
	}

	/**
	 * One page of the project browser, most recently opened first.
	 *
	 * @param q      matched against name and description, case-insensitively and in
	 *               word parts; null or blank means no restriction
	 * @param cursor opaque position from the previous page's {@code nextCursor}; null
	 *               for the first page
	 * @param limit  page size, between {@value #MIN_PAGE_SIZE} and {@value #MAX_PAGE_SIZE}
	 */
	@Transactional(readOnly = true)
	public ProjectDtos.Page list(String q, String cursor, int limit) {
		if (limit < MIN_PAGE_SIZE || limit > MAX_PAGE_SIZE) {
			throw new BadRequestException("limit muss zwischen " + MIN_PAGE_SIZE + " und " + MAX_PAGE_SIZE
					+ " liegen. Angegeben war " + limit + ".");
		}
		ProjectCursor decoded = cursor == null ? null : ProjectCursor.decode(cursor);

		// One extra row is what answers "is there a next page" without a second,
		// separate count query -- the same trick FeatureQueryService.list uses.
		List<ProjectSummaryRow> rows = repository.findPage(
				searchPattern(q),
				decoded == null ? null : decoded.lastOpenedAt(),
				decoded == null ? null : decoded.createdAt(),
				decoded == null ? null : decoded.id(),
				limit + 1);

		boolean hasMore = rows.size() > limit;
		List<ProjectSummaryRow> page = hasMore ? rows.subList(0, limit) : rows;

		String nextCursor = null;
		if (hasMore) {
			ProjectSummaryRow last = page.get(page.size() - 1);
			nextCursor = new ProjectCursor(last.getLastOpenedAt(), last.getCreatedAt(), last.getId()).encode();
		}

		return new ProjectDtos.Page(page.stream().map(ProjectService::toSummary).toList(), nextCursor);
	}

	@Transactional
	public ProjectDtos.Detail create(ProjectDtos.CreateRequest request) {
		int srid = request.srid() == null ? DEFAULT_SRID : request.srid();
		requireKnownSrid(srid);
		requireKnownBasemap(request.basemap());

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

	/**
	 * @param origin who is writing, from {@code X-Hgis-Client}, or null. Only carried
	 *     through to {@link ProjectViewportChanged} -- and only when {@code center} or
	 *     {@code zoom} actually moved, see below -- so the writer can tell its own echo
	 *     apart from someone else's change; nothing here reads it.
	 */
	@Transactional
	public ProjectDtos.Detail update(UUID id, ProjectDtos.UpdateRequest request, String origin) {
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
			requireKnownBasemap(request.basemap());
			project.setBasemap(request.basemap());
		}
		if (request.basemapOpacity() != null) {
			project.setBasemapOpacity(request.basemapOpacity());
		}

		// Captured before either field is touched, so the comparison below is always
		// against what the project stood at a moment ago, not against a value this same
		// request has already overwritten.
		Point previousCenter = project.getCenter();
		Double previousZoom = project.getZoom();

		if (request.center() != null) {
			project.setCenter(toPoint(request.center()));
		}
		if (request.zoom() != null) {
			project.setZoom(request.zoom());
		}

		ProjectDtos.Detail detail = withCounts(project);

		// Fired for center/zoom alone, and only when one of them actually moved -- see
		// ProjectViewportChanged for why a plain rename must not reach this at all.
		if (!sameCoordinate(previousCenter, project.getCenter()) || !sameZoom(previousZoom, project.getZoom())) {
			events.publishEvent(new ProjectViewportChanged(id, origin));
		}

		return detail;
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

	/**
	 * The client's saved view state for this project, cleaned up against the layers that
	 * still exist. A layer that was deleted since the state was saved simply falls out of
	 * {@code layers}; if it was the active one, {@code activeLayerId} comes back null. This
	 * is the only place that cleanup happens -- deleting a layer needs no cleanup step of
	 * its own, see CONTRACT.md phase 17.
	 */
	@Transactional(readOnly = true)
	public ProjectDtos.ViewState viewState(UUID id) {
		Project project = require(id);
		ProjectDtos.ViewState stored = readViewState(project.getViewState());

		Set<UUID> existingLayerIds = Set.copyOf(layerRepository.findIdsByProjectId(id));
		Map<UUID, ProjectDtos.LayerViewState> layers = new LinkedHashMap<>();
		stored.layers().forEach((layerId, layerState) -> {
			if (existingLayerIds.contains(layerId)) {
				layers.put(layerId, layerState);
			}
		});

		UUID activeLayerId = stored.activeLayerId() != null && existingLayerIds.contains(stored.activeLayerId())
				? stored.activeLayerId()
				: null;
		return new ProjectDtos.ViewState(1, activeLayerId, layers);
	}

	/**
	 * Replaces the client's saved view state wholesale. What a layer's {@code sort.field}
	 * refers to is deliberately not checked here against {@code layer_field} -- a field can
	 * be dropped after this is saved, so a check at write time would give no guarantee. The
	 * attribute table's own query already reports "Unbekanntes Sortierfeld" when that
	 * happens; this method must not build a second check for the same thing.
	 *
	 * <p>The write goes out as {@link ProjectViewStateChanged}, which is what reaches every
	 * open live channel once this transaction has committed -- never before, or a listener
	 * would read the state back and get the value this call is about to replace.
	 *
	 * @param origin who is writing, from {@code X-Hgis-Client}, or null. Only carried
	 *     through to the event so the writer can tell its own echo apart from someone
	 *     else's change; nothing here reads it.
	 */
	@Transactional
	public void updateViewState(UUID id, ProjectDtos.ViewState request, String origin) {
		require(id);
		String document = objectMapper.writeValueAsString(validateViewState(request));

		// Written and bumped in one statement rather than through the entity: two clients
		// saving at the same time would otherwise both read version N and both write N+1,
		// and a receiver that has already seen N+1 would take the second change for the
		// first one and never read it. RETURNING is what makes the new value available
		// without a second read that could see a third client's write instead.
		// project_touch_updated_at keeps updated_at honest for this path, see V1.
		long version = jdbc.sql("""
						UPDATE gis_meta.project
						   SET view_state = CAST(:document AS jsonb),
						       view_state_version = view_state_version + 1
						 WHERE id = :id
						RETURNING view_state_version
						""")
				.param("document", document)
				.param("id", id)
				.query(Long.class)
				.single();

		events.publishEvent(new ProjectViewStateChanged(id, version, origin));
	}

	// --- helpers ---------------------------------------------------------------

	/**
	 * @return whether two centres are the same, within {@link #VIEWPORT_EPSILON} -- null
	 *     is its own case, since {@code Point} has no coordinates to compare
	 */
	private static boolean sameCoordinate(Point previous, Point next) {
		if (previous == null || next == null) {
			return previous == next;
		}
		return Math.abs(previous.getX() - next.getX()) <= VIEWPORT_EPSILON
				&& Math.abs(previous.getY() - next.getY()) <= VIEWPORT_EPSILON;
	}

	/** @return whether two zoom levels are the same, within {@link #VIEWPORT_EPSILON} */
	private static boolean sameZoom(Double previous, Double next) {
		if (previous == null || next == null) {
			return Objects.equals(previous, next);
		}
		return Math.abs(previous - next) <= VIEWPORT_EPSILON;
	}

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
					"EPSG:" + srid + " ist der Datenbank nicht bekannt. Es kann nicht als Projekt-CRS dienen.");
		}
	}

	/**
	 * Validates a client-supplied basemap value before it is written -- see
	 * {@link BasemapCatalog} for why (Befund 1, extended for the catalog and the
	 * URL-template case): a value that was neither a known token nor a valid tile URL
	 * used to be accepted with 200 and sat in the database forever, silently falling back
	 * to OSM on every client that ever read it.
	 *
	 * @param basemap null is left alone -- {@link Project}'s constructor and
	 *                {@link #update} both already treat null as "leave the default /
	 *                current value", never as a value to check
	 */
	private void requireKnownBasemap(String basemap) {
		if (basemap == null) {
			return;
		}
		try {
			BasemapCatalog.requireValid(basemap);
		}
		catch (IllegalArgumentException e) {
			throw new FieldValidationException("basemap", e.getMessage());
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
				p.getBasemapOpacity(),
				toLngLat(p.getCenter()), p.getZoom(), toBbox(p.getExtent()),
				layerCount, featureCount,
				p.getLastOpenedAt(), p.getCreatedAt(), p.getUpdatedAt());
	}

	/**
	 * Only ever sees documents {@link #updateViewState} wrote, so a failure means the
	 * column was written past it -- worth a log line, not worth failing the request that
	 * happened to read it.
	 */
	private ProjectDtos.ViewState readViewState(String viewStateJson) {
		if (viewStateJson == null || viewStateJson.isBlank()) {
			return ProjectDtos.ViewState.empty();
		}
		try {
			return objectMapper.readValue(viewStateJson, ProjectDtos.ViewState.class);
		}
		catch (JacksonException ex) {
			log.warn("Stored view state could not be read, treating it as never saved", ex);
			return ProjectDtos.ViewState.empty();
		}
	}

	private ProjectDtos.ViewState validateViewState(ProjectDtos.ViewState request) {
		if (request == null) {
			return ProjectDtos.ViewState.empty();
		}
		Map<UUID, ProjectDtos.LayerViewState> layers = new LinkedHashMap<>();
		request.layers().forEach((layerId, layerState) -> layers.put(layerId, validateLayerViewState(layerState)));
		return new ProjectDtos.ViewState(1, request.activeLayerId(), layers);
	}

	private ProjectDtos.LayerViewState validateLayerViewState(ProjectDtos.LayerViewState state) {
		if (state == null) {
			return new ProjectDtos.LayerViewState(null, null, List.of());
		}
		validateQuery(state.query());
		if (state.selection().size() > MAX_SELECTION_PER_LAYER) {
			throw new BadRequestException("Die Auswahl darf höchstens " + MAX_SELECTION_PER_LAYER
					+ " Objekte je Layer enthalten. Angegeben waren " + state.selection().size() + ".");
		}
		return state;
	}

	private void validateQuery(ProjectDtos.Query query) {
		if (query == null) {
			return;
		}
		if (!ProjectDtos.QUERY_MODE_SEARCH.equals(query.mode()) && !ProjectDtos.QUERY_MODE_FILTER.equals(query.mode())) {
			throw new BadRequestException("Unbekannter Wert für query.mode: " + query.mode()
					+ ". Erlaubt sind " + ProjectDtos.QUERY_MODE_FILTER + ", " + ProjectDtos.QUERY_MODE_SEARCH + ".");
		}
		if (query.text() != null && query.text().length() > MAX_QUERY_TEXT_LENGTH) {
			throw new BadRequestException(
					"query.text darf höchstens " + MAX_QUERY_TEXT_LENGTH + " Zeichen lang sein");
		}
	}

	private Point toPoint(double[] lngLat) {
		if (lngLat.length != 2) {
			throw new BadRequestException("center muss genau zwei Werte enthalten: [lng, lat]");
		}
		double lng = lngLat[0];
		double lat = lngLat[1];
		if (lng < -180 || lng > 180 || lat < -90 || lat > 90) {
			throw new BadRequestException(
					"center liegt außerhalb des gültigen Bereichs für EPSG:4326");
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

	private static ProjectDtos.Summary toSummary(ProjectSummaryRow row) {
		return new ProjectDtos.Summary(
				row.getId(), row.getName(), row.getDescription(), row.getSrid(),
				row.getLayerCount(), row.getFeatureCount(),
				row.getLastOpenedAt(), row.getCreatedAt(),
				toLngLat(row.getCenterLng(), row.getCenterLat()),
				row.getZoom(),
				toBbox(row.getExtentMinLng(), row.getExtentMinLat(), row.getExtentMaxLng(), row.getExtentMaxLat()),
				row.getBasemap());
	}

	private static double[] toLngLat(Double lng, Double lat) {
		return lng == null || lat == null ? null : new double[] { lng, lat };
	}

	private static double[] toBbox(Double minLng, Double minLat, Double maxLng, Double maxLat) {
		return minLng == null || minLat == null || maxLng == null || maxLat == null
				? null
				: new double[] { minLng, minLat, maxLng, maxLat };
	}

	/**
	 * @return an ILIKE pattern with {@code %} and {@code _} escaped so a search term is
	 *     matched literally rather than as a wildcard pattern -- the same rule
	 *     {@code TextSearch} in the features package already applies to a layer's rows --
	 *     or null when there is nothing to search for
	 */
	private static String searchPattern(String q) {
		if (q == null || q.isBlank()) {
			return null;
		}
		String escaped = q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return "%" + escaped + "%";
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
