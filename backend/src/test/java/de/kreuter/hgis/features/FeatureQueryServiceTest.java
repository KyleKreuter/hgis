package de.kreuter.hgis.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.dto.FeatureDtos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Feature queries against a real PostGIS table.
 *
 * The fixture is deliberately awkward: duplicated sort values and NULLs, because those
 * are the two things keyset paging gets wrong. A layer where every value is distinct
 * would pass with a broken cursor.
 *
 * Built once for the class ({@link TestInstance.Lifecycle#PER_CLASS}) -- nothing here
 * writes, so there is nothing to isolate between methods.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeatureQueryServiceTest {

	@Autowired
	private FeatureQueryService service;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private LayerFieldRepository fieldRepository;

	private Project project;
	private Layer layer;
	private String tableName;

	/** 12 rows: four share a street name, three have none at all. */
	private static final List<String[]> ROWS = List.of(
			new String[] { "Alsterufer", "10" },
			new String[] { "Alsterufer", "20" },
			new String[] { "Alsterufer", "30" },
			new String[] { "Alsterufer", "40" },
			new String[] { "Böhmkenstraße", "15" },
			new String[] { "Große Elbstraße", "25" },
			new String[] { "Müllerstraße", "35" },
			new String[] { "Rödingsmarkt", "45" },
			new String[] { "Ölmühle", "55" },
			new String[] { null, "60" },
			new String[] { null, "70" },
			new String[] { null, null });

	@BeforeAll
	void createLayer() {
		project = projectRepository.saveAndFlush(
				new Project("Feature-Test " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom    geometry(MultiPolygon, 25832) NOT NULL,
				    strasse text,
				    hoehe   double precision
				)
				""".formatted(table)).update();

		int index = 0;
		for (String[] row : ROWS) {
			// Spread the geometries along a line so a bbox can select a known prefix.
			double x = 550000 + index * 100;
			jdbc.sql("INSERT INTO " + table + " (geom, strasse, hoehe) VALUES ("
							+ "ST_Multi(ST_MakeEnvelope(:x, 5930000, :x2, 5930100, 25832)), :s, :h)")
					.param("x", x)
					.param("x2", x + 50)
					.param("s", row[0])
					.param("h", row[1] == null ? null : Double.parseDouble(row[1]))
					.update();
			index++;
		}

		Layer newLayer = new Layer(layerId, project, "Adressen", tableName, "MULTIPOLYGON", 25832);
		newLayer.setFeatureCount(ROWS.size());
		layer = layerRepository.saveAndFlush(newLayer);

		fieldRepository.saveAndFlush(new LayerField(layer, "Straße", "strasse", "text", 0));
		fieldRepository.saveAndFlush(new LayerField(layer, "Höhe", "hoehe", "double precision", 1));
	}

	@AfterAll
	void dropLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.deleteById(layer.getId());
		projectRepository.deleteById(project.getId());
	}

	private Project modeProject;
	private Layer modeLayer;
	private String modeTableName;

	/** The rectangle every selection-mode test queries against, in EPSG:4326. */
	private static final double[] SELECTION_RECT = { 10.00, 50.00, 10.10, 50.10 };

	/**
	 * A second, small fixture dedicated to the {@code intersects}/{@code contains}
	 * selection modes. The paging fixture above is deliberately made of equal,
	 * non-overlapping envelopes along a line -- useful for cursor tests, useless for
	 * proving that {@code &&} alone is not enough to select by geometry.
	 *
	 * <p>This layer's storage CRS is EPSG:4326, so {@link #SELECTION_RECT} can be written
	 * directly in the same coordinates as the fixture rows, without a mental detour
	 * through UTM to check which one lies where.
	 */
	@BeforeAll
	void createModeLayer() {
		modeProject = projectRepository.saveAndFlush(
				new Project("Auswahl-Test " + UUID.randomUUID(), null, 4326, "osm"));

		UUID layerId = UUID.randomUUID();
		modeTableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(modeTableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom  geometry(MultiPolygon, 4326) NOT NULL,
				    label text
				)
				""".formatted(table)).update();

		jdbc.sql("""
				INSERT INTO %s (geom, label) VALUES
				-- Fully inside SELECTION_RECT: found by both intersects and contains.
				(ST_Multi(ST_MakeEnvelope(10.02, 50.02, 10.04, 50.04, 4326)), 'innen'),
				-- Straddles the rectangle's right edge (10.10): intersects it, is not
				-- fully contained by it.
				(ST_Multi(ST_MakeEnvelope(10.08, 50.03, 10.15, 50.05, 4326)), 'kante'),
				-- An L made of two arms that individually never reach the rectangle. Their
				-- combined envelope overlaps it -- the && prefilter alone would wrongly
				-- include this row -- but neither arm actually touches it, so both exact
				-- modes must exclude it.
				(ST_Multi(ST_Collect(ARRAY[
				    ST_MakeEnvelope(10.15, 50.05, 10.20, 50.20, 4326),
				    ST_MakeEnvelope(10.05, 50.15, 10.20, 50.20, 4326)
				])), 'nur-bbox')
				""".formatted(table)).update();

		Layer newLayer = new Layer(layerId, modeProject, "Auswahl", modeTableName, "MULTIPOLYGON", 4326);
		newLayer.setFeatureCount(3);
		modeLayer = layerRepository.saveAndFlush(newLayer);

		fieldRepository.saveAndFlush(new LayerField(modeLayer, "Label", "label", "text", 0));
	}

	@AfterAll
	void dropModeLayer() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(modeTableName)).update();
		layerRepository.deleteById(modeLayer.getId());
		projectRepository.deleteById(modeProject.getId());
	}

	private FeatureQueryService.Query modeQuery(String mode) {
		return new FeatureQueryService.Query(null, false, null, SELECTION_RECT, mode, false, null, 100);
	}

	private List<Object> labelsOf(FeatureDtos.Page page) {
		return page.features().stream().map(feature -> feature.properties().get("label")).toList();
	}

	private FeatureQueryService.Query query(String sort, boolean desc, String cursor, int size) {
		return new FeatureQueryService.Query(sort, desc, null, null, null, false, cursor, size);
	}

	/** Walks every page and returns the values of one column, in the order delivered. */
	private List<Object> pageThrough(String sort, boolean desc, int pageSize, String column) {
		List<Object> collected = new ArrayList<>();
		String cursor = null;
		int guard = 0;

		do {
			FeatureDtos.Page page = service.list(layer.getId(), query(sort, desc, cursor, pageSize));
			page.features().forEach(feature -> collected.add(feature.properties().get(column)));
			cursor = page.nextCursor();
		}
		while (cursor != null && ++guard < 50);

		return collected;
	}

	@Test
	@DisplayName("paging by fid returns every row exactly once")
	void pagesThroughEveryRow() {
		List<Object> all = pageThrough(null, false, 5, "strasse");

		assertThat(all).hasSize(ROWS.size());
	}

	@Test
	@DisplayName("a page size that divides the total evenly does not produce a phantom page")
	void stopsCleanlyOnAnExactMultiple() {
		// 12 rows in pages of 4: the third page fills exactly, and only fetching one row
		// beyond the page tells us there is nothing after it.
		FeatureDtos.Page third = service.list(layer.getId(),
				query(null, false, service.list(layer.getId(),
						query(null, false, service.list(layer.getId(), query(null, false, null, 4))
								.nextCursor(), 4)).nextCursor(), 4));

		assertThat(third.features()).hasSize(4);
		assertThat(third.nextCursor()).as("no fourth page").isNull();
	}

	@Test
	@DisplayName("duplicate sort values do not make rows repeat or vanish across pages")
	void handlesDuplicateSortValues() {
		// Four rows share "Alsterufer". Without fid as tie-breaker, a page boundary
		// falling inside that block would either skip rows or serve them twice.
		List<Object> paged = pageThrough("Straße", false, 2, "hoehe");
		List<Object> single = pageThrough("Straße", false, 100, "hoehe");

		assertThat(paged).hasSize(ROWS.size());
		assertThat(paged).containsExactlyElementsOf(single);
	}

	@Test
	@DisplayName("NULLs sort last in both directions and page correctly")
	void handlesNullsInTheSortColumn() {
		List<Object> ascending = pageThrough("Straße", false, 3, "strasse");
		List<Object> descending = pageThrough("Straße", true, 3, "strasse");

		assertThat(ascending).hasSize(ROWS.size());
		assertThat(descending).hasSize(ROWS.size());
		assertThat(ascending.subList(ascending.size() - 3, ascending.size()))
				.as("three NULL rows at the end, ascending")
				.containsOnlyNulls();
		assertThat(descending.subList(descending.size() - 3, descending.size()))
				.as("NULLS LAST applies descending too, so NULLs never lead a page")
				.containsOnlyNulls();

		// Compared as a reversal rather than against named streets: where "Ölmühle" lands
		// is decided by the database collation, not by this code. The property that
		// actually matters -- one direction is the exact mirror of the other -- holds
		// under any collation.
		List<Object> ascendingNames = ascending.stream().filter(Objects::nonNull).toList();
		List<Object> descendingNames = descending.stream().filter(Objects::nonNull).toList();
		assertThat(descendingNames)
				.containsExactlyElementsOf(ascendingNames.reversed());
		assertThat(ascendingNames.get(0)).isEqualTo("Alsterufer");
	}

	@Test
	@DisplayName("sorting descending by a numeric column with NULLs stays complete")
	void handlesNumericSort() {
		List<Object> values = pageThrough("Höhe", true, 4, "hoehe");

		assertThat(values).hasSize(ROWS.size());
		assertThat(values.get(0)).isEqualTo(70.0);
		assertThat(values.get(values.size() - 1)).as("the single NULL height comes last").isNull();
	}

	@Test
	@DisplayName("sorting by fid alone still honours the direction")
	void pagesDescendingByFid() {
		// "Newest first" is this query, and silently serving the opposite would be the
		// kind of wrong nobody checks.
		List<Object> descending = pageThrough("fid", true, 5, "hoehe");
		List<Object> ascending = pageThrough("fid", false, 5, "hoehe");

		assertThat(descending).hasSize(ROWS.size());
		assertThat(descending).containsExactlyElementsOf(ascending.reversed());
	}

	@Test
	@DisplayName("the total is reported once, on the first page")
	void reportsTheTotalOnlyOnTheFirstPage() {
		FeatureDtos.Page first = service.list(layer.getId(), query(null, false, null, 5));
		FeatureDtos.Page second = service.list(layer.getId(), query(null, false, first.nextCursor(), 5));

		assertThat(first.totalCount()).isEqualTo(ROWS.size());
		assertThat(second.totalCount()).as("counting again would rescan for an unchanged number")
				.isNull();
	}

	@Test
	@DisplayName("the total counts the filtered set, not the page")
	void countsTheFilteredSet() {
		FeatureDtos.Page page = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, "Straße = 'Alsterufer'", null, null, false, null, 2));

		assertThat(page.features()).hasSize(2);
		assertThat(page.totalCount()).isEqualTo(4);
	}

	@Test
	void filtersByExpression() {
		FeatureDtos.Page page = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, "\"Höhe\" > 40 AND Straße IS NOT NULL", null, null, false, null, 100));

		assertThat(page.features()).hasSize(2);
		assertThat(page.features()).allSatisfy(feature ->
				assertThat((Double) feature.properties().get("hoehe")).isGreaterThan(40));
	}

	@Test
	@DisplayName("bbox is given in 4326 and selects by the layer's own CRS")
	void filtersByBoundingBox() {
		// The first three rows sit around 550000..550250 in EPSG:25832. Expressed in
		// 4326, that is roughly 9.98 E / 53.54 N.
		FeatureDtos.Page all = service.list(layer.getId(), query(null, false, null, 100));
		FeatureDtos.Page inBox = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, null, new double[] { 9.0, 53.0, 11.0, 54.0 }, null, false, null, 100));

		assertThat(all.features()).hasSize(ROWS.size());
		assertThat(inBox.features())
				.as("the whole fixture lies inside this box")
				.hasSize(ROWS.size());

		FeatureDtos.Page elsewhere = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, null, new double[] { 2.0, 48.0, 3.0, 49.0 }, null, false, null, 100));
		assertThat(elsewhere.features()).as("Paris holds none of it").isEmpty();
	}

	@Test
	void returnsGeometryOnlyWhenAsked() {
		FeatureDtos.Feature without = service.list(layer.getId(), query(null, false, null, 1))
				.features().get(0);
		FeatureDtos.Feature with = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, null, null, null, true, null, 1)).features().get(0);

		assertThat(without.geometry()).isNull();
		assertThat(with.geometry())
				.as("full precision GeoJSON in 4326, the snapping source of plan D.1")
				.contains("\"type\":\"MultiPolygon\"")
				.contains("coordinates");
	}

	@Test
	void readsASingleFeatureWithItsRowVersion() {
		long fid = service.list(layer.getId(), query(null, false, null, 1)).features().get(0).fid();

		FeatureDtos.Feature feature = service.get(layer.getId(), fid);

		assertThat(feature.fid()).isEqualTo(fid);
		assertThat(feature.properties()).containsKeys("strasse", "hoehe");
		assertThat(feature.geometry()).isNotNull();
		assertThat(feature.rowVersion())
				.as("xmin travels from the MVP so optimistic locking needs no migration later")
				.isNotBlank();
	}

	@Test
	void reportsAnUnknownFeature() {
		assertThatThrownBy(() -> service.get(layer.getId(), 999_999))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	void rejectsAnUnknownSortField() {
		assertThatThrownBy(() -> service.list(layer.getId(), query("gibtsnicht", false, null, 10)))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("Unbekanntes Sortierfeld");
	}

	@Test
	void rejectsATamperedCursor() {
		assertThatThrownBy(() -> service.list(layer.getId(), query(null, false, "nicht-base64!!", 10)))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("Cursor");
	}

	@Test
	@DisplayName("without mode, only the && prefilter applies -- today's behaviour is unchanged")
	void selectsByBoundingBoxAloneWithoutAMode() {
		FeatureDtos.Page page = service.list(modeLayer.getId(), modeQuery(null));

		assertThat(labelsOf(page))
				.as("all three rows pass the bbox-only prefilter, including the one whose "
						+ "geometry never actually touches the rectangle")
				.containsExactlyInAnyOrder("innen", "kante", "nur-bbox");
	}

	@Test
	@DisplayName("intersects adds the exact test, dropping the row that only overlaps by envelope")
	void selectsByIntersection() {
		FeatureDtos.Page page = service.list(modeLayer.getId(), modeQuery("intersects"));

		assertThat(labelsOf(page)).containsExactlyInAnyOrder("innen", "kante");
	}

	@Test
	@DisplayName("contains only keeps the row that lies fully inside the rectangle")
	void selectsByContainment() {
		FeatureDtos.Page page = service.list(modeLayer.getId(), modeQuery("contains"));

		assertThat(labelsOf(page)).containsExactly("innen");
	}

	@Test
	@DisplayName("totalCount reflects the exact test too, not just the && prefilter")
	void reportsTheTotalForASelectionMode() {
		FeatureDtos.Page page = service.list(modeLayer.getId(), modeQuery("intersects"));

		assertThat(page.totalCount()).isEqualTo(2);
	}

	@Test
	void rejectsAnUnknownSelectionMode() {
		assertThatThrownBy(() -> service.list(modeLayer.getId(), modeQuery("touches")))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("Auswahlmodus");
	}

	@Test
	void capsAnAbsurdPageSize() {
		FeatureDtos.Page page = service.list(layer.getId(), query(null, false, null, 10_000_000));

		assertThat(page.features()).hasSize(ROWS.size());
	}
}
