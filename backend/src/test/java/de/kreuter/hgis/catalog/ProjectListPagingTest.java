package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The project browser's server-side paging (CONTRACT.md phase 22): {@code GET
 * /api/projects} returns one cursor-paged, optionally searched page instead of the
 * whole table, and the cursor's {@code id} tie-break is what keeps a page boundary
 * from ever skipping or repeating a row.
 *
 * <p>Every project this class creates carries a random marker in its name, and every
 * assertion queries with {@code q} set to that marker (or a substring of it). The
 * database is shared with the rest of the suite, so scoping every read this way is what
 * keeps a test from seeing rows some other test left behind.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectListPagingTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private GeometryFactory geometryFactory;

	private final List<UUID> createdIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		if (!createdIds.isEmpty()) {
			projectRepository.deleteAllById(createdIds);
		}
	}

	@Test
	@DisplayName("the first page with limit=2 delivers two entries and a nextCursor")
	void firstPageRespectsLimitAndCarriesACursor() throws Exception {
		String marker = marker();
		for (int i = 0; i < 3; i++) {
			createProject(marker + "-" + i, null);
		}

		JsonNode page = list(marker, null, 2);

		assertThat(page.get("items")).hasSize(2);
		assertThat(page.get("nextCursor").isNull()).isFalse();
	}

	@Test
	@DisplayName("paging through with a small limit reproduces exactly the large-limit order, without duplicates")
	void pagingReproducesTheFullOrderWithoutDuplicates() throws Exception {
		String marker = marker();
		for (int i = 0; i < 5; i++) {
			createProject(marker + "-" + i, null);
		}

		List<UUID> wholeList = ids(list(marker, null, 100));
		assertThat(wholeList).hasSize(5);

		List<UUID> walked = new ArrayList<>();
		String cursor = null;
		JsonNode lastPage = null;
		do {
			lastPage = list(marker, cursor, 2);
			walked.addAll(ids(lastPage));
			cursor = lastPage.get("nextCursor").isNull() ? null : lastPage.get("nextCursor").asString();
		}
		while (cursor != null);

		assertThat(walked).as("walked pages concatenate to the same order as one large page")
				.containsExactlyElementsOf(wholeList);
		assertThat(lastPage.get("nextCursor").isNull())
				.as("the last page carries no cursor")
				.isTrue();
	}

	@Test
	@DisplayName("two projects with the same last_opened_at and the same created_at each appear exactly once")
	void identicalTimestampsStillAppearExactlyOnce() throws Exception {
		String marker = marker();
		UUID first = createProject(marker + "-a", null);
		UUID second = createProject(marker + "-b", null);

		Instant sameOpened = Instant.now().minusSeconds(60);
		Instant sameCreated = Instant.now().minusSeconds(120);
		setTimestamps(first, sameOpened, sameCreated);
		setTimestamps(second, sameOpened, sameCreated);

		List<UUID> walked = new ArrayList<>();
		String cursor = null;
		do {
			JsonNode page = list(marker, cursor, 1);
			walked.addAll(ids(page));
			cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
		}
		while (cursor != null);

		assertThat(walked).as("id is the tie-break -- without it, one of the two would be skipped or repeated")
				.containsExactlyInAnyOrder(first, second);
	}

	@Test
	@DisplayName("projects that were never opened stand behind all opened ones")
	void neverOpenedProjectsComeLast() throws Exception {
		String marker = marker();
		UUID opened = createProject(marker + "-opened", null);
		UUID neverOpened = createProject(marker + "-never", null);
		setTimestamps(opened, Instant.now().minusSeconds(10), Instant.now().minusSeconds(3600));
		// neverOpened keeps last_opened_at = null, as every freshly created project does.

		List<UUID> walked = ids(list(marker, null, 100));

		assertThat(walked).containsExactly(opened, neverOpened);
	}

	@Test
	@DisplayName("q matches word parts in name and description, case-insensitively, across page boundaries")
	void searchMatchesWordPartsAcrossPages() throws Exception {
		// Unique per run, so this can never collide with a substring some other test or
		// leftover row happens to contain.
		String needle = "Bauprojekt" + UUID.randomUUID().toString().substring(0, 8);

		UUID matchInName = createProject("Vorhaben-" + needle + "-Nord", null);
		UUID matchInDescription = createProject("Ohne Treffer im Namen",
				"Beschreibung erwähnt " + needle + " beiläufig");
		UUID matchCaseInsensitive = createProject("Zweites-" + needle.toUpperCase() + "-Vorhaben", null);
		createProject("Komplett unbeteiligtes Projekt " + UUID.randomUUID(), "und eine harmlose Beschreibung");

		// Word-part, not the whole token: searching a substring in the middle must still hit.
		String searchTerm = needle.substring(3, needle.length() - 3).toUpperCase();

		List<UUID> walked = new ArrayList<>();
		String cursor = null;
		do {
			JsonNode page = list(searchTerm, cursor, 1);
			walked.addAll(ids(page));
			cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
		}
		while (cursor != null);

		assertThat(walked).containsExactlyInAnyOrder(matchInName, matchInDescription, matchCaseInsensitive);
	}

	@Test
	@DisplayName("limit outside 1..100 and a corrupted cursor are all rejected with 400")
	void rejectsOutOfRangeLimitsAndABrokenCursor() throws Exception {
		mockMvc.perform(get("/api/projects").param("limit", "0")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/projects").param("limit", "101")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/projects").param("cursor", "not-a-valid-cursor!!"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("center, zoom, extent and basemap carry real values when set, and null when the project is bare")
	void newFieldsCarryRealValuesOrNull() throws Exception {
		String marker = marker();
		UUID withValues = createProject(marker + "-with-values", null);
		UUID bare = createProject(marker + "-bare", null);

		Project project = projectRepository.findById(withValues).orElseThrow();
		Point center = geometryFactory.createPoint(new Coordinate(10.0, 53.5));
		center.setSRID(4326);
		project.setCenter(center);
		project.setZoom(12.5);
		Polygon extent = (Polygon) geometryFactory.toGeometry(new Envelope(9.9, 10.1, 53.4, 53.6));
		project.setExtent(extent);
		project.setBasemap("satellite");
		projectRepository.saveAndFlush(project);

		JsonNode page = list(marker, null, 100);
		JsonNode withValuesJson = itemFor(page, withValues);
		JsonNode bareJson = itemFor(page, bare);

		assertThat(withValuesJson.get("center").get(0).asDouble()).isEqualTo(10.0);
		assertThat(withValuesJson.get("center").get(1).asDouble()).isEqualTo(53.5);
		assertThat(withValuesJson.get("zoom").asDouble()).isEqualTo(12.5);
		assertThat(withValuesJson.get("extent").get(0).asDouble()).isEqualTo(9.9);
		assertThat(withValuesJson.get("extent").get(2).asDouble()).isEqualTo(10.1);
		assertThat(withValuesJson.get("basemap").asString()).isEqualTo("satellite");

		assertThat(bareJson.get("center").isNull()).isTrue();
		assertThat(bareJson.get("zoom").isNull()).isTrue();
		assertThat(bareJson.get("extent").isNull()).isTrue();
		assertThat(bareJson.get("basemap").asString())
				.as("every project has a basemap, bare or not")
				.isEqualTo("osm");
	}

	/**
	 * A project opened between two page fetches moves to the front of the order -- ahead
	 * of the cursor position, not behind it. The keyset condition compares against the
	 * cursor's fixed values, so that project simply no longer satisfies "before this
	 * anchor" on the next page: it does not come back duplicated, but it does not appear
	 * a second time either. This locks in that actual, slightly surprising behaviour
	 * rather than asserting an invariant the query cannot give (a leaked-once uniform
	 * pass over every row while rows are being reordered concurrently).
	 */
	@Test
	@DisplayName("a project opened mid-walk moves ahead of the cursor and is not duplicated on the next page")
	void aProjectOpenedBetweenPagesIsNotDuplicated() throws Exception {
		String marker = marker();
		UUID p1 = createProject(marker + "-1", null);
		UUID p2 = createProject(marker + "-2", null);
		UUID p3 = createProject(marker + "-3", null);
		UUID p4 = createProject(marker + "-4", null);

		Instant base = Instant.now().minusSeconds(3600);
		setTimestamps(p1, base.plusSeconds(30), base);
		setTimestamps(p2, base.plusSeconds(20), base);
		setTimestamps(p3, base.plusSeconds(10), base);
		setTimestamps(p4, base, base);

		JsonNode page1 = list(marker, null, 2);
		assertThat(ids(page1)).containsExactly(p1, p2);
		String cursor = page1.get("nextCursor").asString();

		// A client opens p4 -- the project furthest back -- before the walk reaches it.
		mockMvc.perform(get("/api/projects/{id}", p4).param("open", "true")).andExpect(status().isOk());

		JsonNode page2 = list(marker, cursor, 2);

		assertThat(ids(page2)).as("p4 jumped ahead of the cursor's anchor, so it is not on this page")
				.doesNotContain(p4);
		assertThat(ids(page2)).as("p3 was never returned before and is still behind the cursor")
				.containsExactly(p3);
		assertThat(page2.get("nextCursor").isNull())
				.as("p4 moved out of reach of this walk; it is missed, not duplicated")
				.isTrue();

		Set<UUID> walked = new LinkedHashSet<>();
		walked.addAll(ids(page1));
		walked.addAll(ids(page2));
		assertThat(walked).as("no id came back twice")
				.hasSize(ids(page1).size() + ids(page2).size());
	}

	// --- helpers -------------------------------------------------------------------------

	private static String marker() {
		return "PagingTest" + UUID.randomUUID().toString().substring(0, 8);
	}

	/**
	 * CONTRACT.md phase 22, section 2.4: the aggregation must only ever touch the page,
	 * never the whole table. That is a claim about the query plan, so this is the one
	 * test that reads one -- the same approach {@code MvtServiceTest.assertIndexFriendly}
	 * takes for the tile query, and it runs {@link ProjectRepository#PAGE_QUERY} itself
	 * rather than a copy, so the statement under test cannot drift from the one in use.
	 *
	 * <p>What would break without it: moving the {@code LIMIT} out of the CTE and onto
	 * the outer query still returns the right rows, so every other test here stays green
	 * -- while the {@code GROUP BY} silently starts aggregating every project in the
	 * database on every keystroke of the search box. At this project's scale nobody would
	 * notice until it is far too late to notice cheaply.
	 */
	@Test
	@DisplayName("the aggregation only ever sees one page, never the whole table")
	void aggregationTouchesOnlyThePage() throws Exception {
		String marker = marker();
		for (int i = 0; i < 12; i++) {
			createProject(marker + "-" + i, null);
		}

		int limit = 2;

		// The plan is read for the *second* page, not the first: PostgreSQL cannot infer
		// a type for the cursor parameters when they arrive as bare nulls, and a page
		// with a live cursor exercises the keyset condition as well.
		ProjectCursor cursor = ProjectCursor.decode(list(marker, null, limit).get("nextCursor").asString());

		String json = jdbc.sql("EXPLAIN (ANALYZE, FORMAT JSON) " + ProjectRepository.PAGE_QUERY)
				.param("pattern", "%" + marker + "%")
				.param("cursorOpened", cursor.lastOpenedAt() == null ? null : Timestamp.from(cursor.lastOpenedAt()))
				.param("cursorCreated", Timestamp.from(cursor.createdAt()))
				.param("cursorId", cursor.id())
				.param("fetchLimit", limit + 1)
				.query(String.class)
				.single();

		JsonNode plan = MAPPER.readTree(json).get(0).get("Plan");
		List<JsonNode> nodes = new ArrayList<>();
		collectPlanNodes(plan, nodes);

		List<JsonNode> aggregates = nodes.stream()
				.filter(node -> {
					JsonNode type = node.get("Node Type");
					return type != null && type.asString().contains("Aggregate");
				})
				.toList();

		assertThat(aggregates)
				.as("Plan muss eine Aggregation enthalten, sonst prueft dieser Test nichts:%n%s", json)
				.isNotEmpty();

		for (JsonNode aggregate : aggregates) {
			// One row per project on the page, at most -- not one per project in the table.
			assertThat(aggregate.get("Actual Rows").asInt())
					.as("Aggregation darf hoechstens die Seite verarbeiten, Plan war:%n%s", json)
					.isLessThanOrEqualTo(limit + 1);
		}
	}

	private static void collectPlanNodes(JsonNode node, List<JsonNode> out) {
		if (node == null || node.isMissingNode()) {
			return;
		}
		out.add(node);
		JsonNode children = node.get("Plans");
		if (children != null) {
			for (JsonNode child : children) {
				collectPlanNodes(child, out);
			}
		}
	}

	/**
	 * The extent a project reports is aggregated from its layers, not read from
	 * {@code project.extent}. That column is only written on import; a project drawn by
	 * hand never gets one, and one imported and then edited keeps a stale one. Layer
	 * extents, by contrast, are recomputed on every edit.
	 *
	 * <p>What this protects: the browser draws its map preview from this field. Reading
	 * the project column alone leaves every hand-drawn project without a preview, even
	 * though its data is right there -- visible to a user immediately, invisible to every
	 * other test in this class.
	 */
	@Test
	@DisplayName("the extent comes from the layers, even when the project column is empty")
	void extentIsAggregatedFromTheLayers() throws Exception {
		String marker = marker();
		UUID projectId = createProject(marker + "-mit-layern", null);

		// Two layers with known, disjoint extents. The project column stays null, exactly
		// as it does for a project that was drawn rather than imported.
		createLayerWithExtent(projectId, 9.9, 53.5, 10.0, 53.6);
		createLayerWithExtent(projectId, 10.2, 53.7, 10.3, 53.8);

		JsonNode project = list(marker, null, null).get("items").get(0);
		JsonNode extent = project.get("extent");

		assertThat(extent.isNull())
				.as("Projekt mit Layern muss eine Ausdehnung melden, auch ohne project.extent")
				.isFalse();
		assertThat(extent.get(0).asDouble()).isCloseTo(9.9, within(0.0001));
		assertThat(extent.get(1).asDouble()).isCloseTo(53.5, within(0.0001));
		assertThat(extent.get(2).asDouble()).isCloseTo(10.3, within(0.0001));
		assertThat(extent.get(3).asDouble()).isCloseTo(53.8, within(0.0001));
	}

	/** A layer row with nothing but an extent -- enough for the aggregation under test. */
	private void createLayerWithExtent(UUID projectId, double minLng, double minLat,
			double maxLng, double maxLat) {
		UUID layerId = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO gis_meta.layer (id, project_id, name, table_name, geometry_type, srid, extent)
				VALUES (:id, :projectId, :name, :tableName, 'MULTIPOLYGON', 25832,
				        ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326))
				""")
				.param("id", layerId)
				.param("projectId", projectId)
				.param("name", "Layer " + layerId)
				.param("tableName", "layer_" + layerId.toString().replace("-", ""))
				.param("minLng", minLng)
				.param("minLat", minLat)
				.param("maxLng", maxLng)
				.param("maxLat", maxLat)
				.update();
	}

	private UUID createProject(String name, String description) {
		Project project = projectRepository.saveAndFlush(new Project(name, description, 25832, "osm"));
		createdIds.add(project.getId());
		return project.getId();
	}

	private void setTimestamps(UUID id, Instant lastOpenedAt, Instant createdAt) {
		// A plain Instant leaves the driver unable to pick a SQL type for a raw UPDATE
		// (no column context to infer it from, unlike Hibernate's native @Query binding);
		// java.sql.Timestamp carries an explicit one. See FeatureWriter's write path for
		// the same conversion.
		jdbc.sql("UPDATE gis_meta.project SET last_opened_at = :opened, created_at = :created WHERE id = :id")
				.param("opened", Timestamp.from(lastOpenedAt))
				.param("created", Timestamp.from(createdAt))
				.param("id", id)
				.update();
	}

	private JsonNode list(String q, String cursor, Integer limit) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/projects");
		if (q != null) {
			request.param("q", q);
		}
		if (cursor != null) {
			request.param("cursor", cursor);
		}
		if (limit != null) {
			request.param("limit", String.valueOf(limit));
		}
		String body = mockMvc.perform(request)
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		return MAPPER.readTree(body);
	}

	private static List<UUID> ids(JsonNode page) {
		List<UUID> result = new ArrayList<>();
		for (JsonNode item : page.get("items")) {
			result.add(UUID.fromString(item.get("id").asString()));
		}
		return result;
	}

	private static JsonNode itemFor(JsonNode page, UUID id) {
		for (JsonNode item : page.get("items")) {
			if (item.get("id").asString().equals(id.toString())) {
				return item;
			}
		}
		throw new AssertionError("project " + id + " not found in page " + page);
	}
}
