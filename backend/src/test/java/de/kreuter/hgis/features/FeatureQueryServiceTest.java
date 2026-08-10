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

	private FeatureQueryService.Query query(String sort, boolean desc, String cursor, int size) {
		return new FeatureQueryService.Query(sort, desc, null, null, false, cursor, size);
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
				null, false, "Straße = 'Alsterufer'", null, false, null, 2));

		assertThat(page.features()).hasSize(2);
		assertThat(page.totalCount()).isEqualTo(4);
	}

	@Test
	void filtersByExpression() {
		FeatureDtos.Page page = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, "\"Höhe\" > 40 AND Straße IS NOT NULL", null, false, null, 100));

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
				null, false, null, new double[] { 9.0, 53.0, 11.0, 54.0 }, false, null, 100));

		assertThat(all.features()).hasSize(ROWS.size());
		assertThat(inBox.features())
				.as("the whole fixture lies inside this box")
				.hasSize(ROWS.size());

		FeatureDtos.Page elsewhere = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, null, new double[] { 2.0, 48.0, 3.0, 49.0 }, false, null, 100));
		assertThat(elsewhere.features()).as("Paris holds none of it").isEmpty();
	}

	@Test
	void returnsGeometryOnlyWhenAsked() {
		FeatureDtos.Feature without = service.list(layer.getId(), query(null, false, null, 1))
				.features().get(0);
		FeatureDtos.Feature with = service.list(layer.getId(), new FeatureQueryService.Query(
				null, false, null, null, true, null, 1)).features().get(0);

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
	void capsAnAbsurdPageSize() {
		FeatureDtos.Page page = service.list(layer.getId(), query(null, false, null, 10_000_000));

		assertThat(page.features()).hasSize(ROWS.size());
	}
}
