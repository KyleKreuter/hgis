package de.kreuter.hgis.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The two endpoints the symbology UI asks before it proposes a classification.
 *
 * <p>The fixture is deliberately tiny and fully known: ten features carrying 0, 10, ...,
 * 90 inhabitants plus two without a value at all, so every boundary in the expectations
 * below can be worked out by hand rather than read off a run.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ClassificationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private LayerFieldRepository layerFieldRepository;

	private Project project;
	private Layer layer;
	private String tableName;

	@BeforeEach
	void setUp() {
		project = projectRepository.saveAndFlush(
				new Project("Klassifizierungs-Testprojekt " + UUID.randomUUID(), null, 25832, "osm"));

		UUID layerId = UUID.randomUUID();
		tableName = SqlIdentifier.tableName(layerId);
		String table = SqlIdentifier.quoteLayerTable(tableName);

		jdbc.sql("""
				CREATE TABLE %s (
				    fid         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    einwohner   integer,
				    nutzungsart text,
				    ohne_werte  integer,
				    geom        geometry(MultiPolygon, 25832) NOT NULL
				)
				""".formatted(table)).update();

		jdbc.sql("""
				INSERT INTO %s (einwohner, nutzungsart, ohne_werte, geom)
				SELECT v.einwohner, v.nutzungsart, NULL,
				       ST_Multi(ST_MakeEnvelope(400000 + v.n * 20, 5600000,
				                                400010 + v.n * 20, 5600010, 25832))
				FROM (VALUES
				    ( 1,    0::integer, 'Wohnen'::text),
				    ( 2,   10,          'Wohnen'),
				    ( 3,   20,          'Wohnen'),
				    ( 4,   30,          'Wohnen'),
				    ( 5,   40,          'Wohnen'),
				    ( 6,   50,          'Wohnen'),
				    ( 7,   60,          'Gewerbe'),
				    ( 8,   70,          'Gewerbe'),
				    ( 9,   80,          'Gewerbe'),
				    (10,   90,          'Wald'),
				    (11, NULL,          NULL),
				    (12, NULL,          NULL)
				) AS v(n, einwohner, nutzungsart)
				""".formatted(table)).update();

		layer = layerRepository.saveAndFlush(
				new Layer(layerId, project, "Ortsteile", tableName, "MULTIPOLYGON", 25832));

		layerFieldRepository.saveAndFlush(new LayerField(layer, "Einwohner", "einwohner", "integer", 0));
		layerFieldRepository.saveAndFlush(new LayerField(layer, "Nutzungsart", "nutzungsart", "text", 1));
		layerFieldRepository.saveAndFlush(new LayerField(layer, "Ohne Werte", "ohne_werte", "integer", 2));
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(tableName)).update();
		layerRepository.findById(layer.getId()).ifPresent(layerRepository::delete);
		projectRepository.deleteById(project.getId());
	}

	// --- classify ---------------------------------------------------------------------

	@Test
	@DisplayName("quantile puts the same number of features in each class")
	void quantileBreaks() throws Exception {
		classify("Einwohner", "quantile", 2)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.field").value("einwohner"))
				.andExpect(jsonPath("$.method").value("quantile"))
				.andExpect(jsonPath("$.breaks", Matchers.hasSize(3)))
				.andExpect(jsonPath("$.breaks[0]").value(0.0))
				.andExpect(jsonPath("$.breaks[1]").value(45.0))
				.andExpect(jsonPath("$.breaks[2]").value(90.0))
				.andExpect(jsonPath("$.min").value(0.0))
				.andExpect(jsonPath("$.max").value(90.0))
				.andExpect(jsonPath("$.nullCount").value(2));
	}

	@Test
	@DisplayName("equalInterval splits the range, not the features")
	void equalIntervalBreaks() throws Exception {
		classify("einwohner", "equalInterval", 3)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.breaks", Matchers.hasSize(4)))
				.andExpect(jsonPath("$.breaks[0]").value(0.0))
				.andExpect(jsonPath("$.breaks[1]").value(30.0))
				.andExpect(jsonPath("$.breaks[2]").value(60.0))
				.andExpect(jsonPath("$.breaks[3]").value(90.0));
	}

	@Test
	@DisplayName("naturalBreaks cuts at the ntile bucket boundaries")
	void naturalBreaks() throws Exception {
		// Ten values in two buckets of five: the second one starts at 50.
		classify("einwohner", "naturalBreaks", 2)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.breaks", Matchers.hasSize(3)))
				.andExpect(jsonPath("$.breaks[0]").value(0.0))
				.andExpect(jsonPath("$.breaks[1]").value(50.0))
				.andExpect(jsonPath("$.breaks[2]").value(90.0));
	}

	@Test
	@DisplayName("a column without a single value classifies to nothing rather than failing")
	void classifyingAnEmptyColumn() throws Exception {
		classify("ohne_werte", "quantile", 4)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.breaks", Matchers.hasSize(0)))
				.andExpect(jsonPath("$.min").doesNotExist())
				.andExpect(jsonPath("$.max").doesNotExist())
				.andExpect(jsonPath("$.nullCount").value(12));
	}

	@Test
	@DisplayName("more classes than distinct values leaves no empty class behind")
	void breaksNeverRepeat() throws Exception {
		classify("einwohner", "quantile", 12)
				.andExpect(status().isOk())
				// Strictly ascending, so a step expression built from this stays valid.
				.andExpect(jsonPath("$.breaks[0]").value(0.0))
				.andExpect(jsonPath("$.breaks[-1]").value(90.0));
	}

	@Test
	void rejectsClassifyingATextColumn() throws Exception {
		classify("Nutzungsart", "quantile", 5).andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAnUnknownField() throws Exception {
		classify("existiert_nicht", "quantile", 5).andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAnUnknownMethod() throws Exception {
		classify("einwohner", "jenks", 5).andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAClassCountOutsideTwoToTwelve() throws Exception {
		classify("einwohner", "quantile", 1).andExpect(status().isBadRequest());
		classify("einwohner", "quantile", 13).andExpect(status().isBadRequest());
	}

	@Test
	void returnsNotFoundForAnUnknownLayer() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}/classify", UUID.randomUUID())
						.param("field", "einwohner"))
				.andExpect(status().isNotFound());
	}

	// --- values -----------------------------------------------------------------------

	@Test
	@DisplayName("values come back most frequent first, with null as a value of its own")
	void valuesByFrequency() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}/values", layer.getId())
						.param("field", "Nutzungsart"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.field").value("nutzungsart"))
				.andExpect(jsonPath("$.values", Matchers.hasSize(4)))
				.andExpect(jsonPath("$.values[0].value").value("Wohnen"))
				.andExpect(jsonPath("$.values[0].count").value(6))
				.andExpect(jsonPath("$.values[1].value").value("Gewerbe"))
				.andExpect(jsonPath("$.values[1].count").value(3))
				.andExpect(jsonPath("$.values[2].value").doesNotExist())
				.andExpect(jsonPath("$.values[2].count").value(2))
				.andExpect(jsonPath("$.values[3].value").value("Wald"))
				.andExpect(jsonPath("$.truncated").value(false));
	}

	@Test
	@DisplayName("a limit shorter than the number of distinct values says so")
	void valuesReportTruncation() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}/values", layer.getId())
						.param("field", "nutzungsart")
						.param("limit", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.values", Matchers.hasSize(2)))
				.andExpect(jsonPath("$.truncated").value(true));
	}

	@Test
	@DisplayName("a numeric column can be categorized too -- codes are values like any other")
	void valuesOfANumericColumn() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}/values", layer.getId())
						.param("field", "einwohner"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.values", Matchers.hasSize(11)))
				.andExpect(jsonPath("$.truncated").value(false));
	}

	@Test
	void rejectsValuesForAnUnknownField() throws Exception {
		mockMvc.perform(get("/api/layers/{layerId}/values", layer.getId())
						.param("field", "existiert_nicht"))
				.andExpect(status().isBadRequest());
	}

	private ResultActions classify(String field, String method, int classes) throws Exception {
		return mockMvc.perform(get("/api/layers/{layerId}/classify", layer.getId())
				.param("field", field)
				.param("method", method)
				.param("classes", String.valueOf(classes)));
	}
}
