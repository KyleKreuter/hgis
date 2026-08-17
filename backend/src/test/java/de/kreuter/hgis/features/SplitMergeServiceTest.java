package de.kreuter.hgis.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.withinPercentage;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.catalog.Layer;
import de.kreuter.hgis.catalog.LayerField;
import de.kreuter.hgis.catalog.LayerFieldRepository;
import de.kreuter.hgis.catalog.LayerRepository;
import de.kreuter.hgis.catalog.Project;
import de.kreuter.hgis.catalog.ProjectRepository;
import de.kreuter.hgis.common.BadRequestException;
import de.kreuter.hgis.common.ConflictException;
import de.kreuter.hgis.common.NotFoundException;
import de.kreuter.hgis.common.SqlIdentifier;
import de.kreuter.hgis.features.dto.SplitMergeDtos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Splitting and merging against a real PostGIS table (CONTRACT.md section 12).
 *
 * <p>Nothing here is mocked, and that is the point: both operations exist because PostGIS
 * computes the geometry. A test that stubbed the database would prove only that the Java
 * around it compiles.
 *
 * <p>Like the edit batch's test, every method builds its own layer -- both operations
 * change the row count, and a leftover row would make that meaningless.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SplitMergeServiceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** A square around 9.98 E / 53.54 N -- Hamburg, where the rest of the fixtures live. */
	private static final String SQUARE = """
			{"type":"Polygon","coordinates":[[[9.98,53.54],[9.99,53.54],[9.99,53.55],[9.98,53.55],[9.98,53.54]]]}
			""";

	/** Shares the eastern edge of {@link #SQUARE}, so the two have a union without a gap. */
	private static final String SQUARE_EAST = """
			{"type":"Polygon","coordinates":[[[9.99,53.54],[10.0,53.54],[10.0,53.55],[9.99,53.55],[9.99,53.54]]]}
			""";

	/** Touches nothing else in this file -- what makes a merge produce a real MULTIPOLYGON. */
	private static final String SQUARE_FAR = """
			{"type":"Polygon","coordinates":[[[10.1,53.6],[10.11,53.6],[10.11,53.61],[10.1,53.61],[10.1,53.6]]]}
			""";

	/** Cuts {@link #SQUARE} well off centre, so which part is the larger one is not in doubt. */
	private static final String CUT_OFF_CENTRE = """
			{"type":"LineString","coordinates":[[9.9825,53.53],[9.9825,53.56]]}
			""";

	/**
	 * The unit square, and a cut straight through its middle.
	 *
	 * <p>Off Hamburg on purpose, unlike everything else here: these are the coordinates
	 * that make two halves come out <em>exactly</em> equal. {@code 9.99 - 9.985} and
	 * {@code 9.985 - 9.98} are not the same double, so the Hamburg square has no centre a
	 * tie-break could ever be observed at.
	 */
	private static final String UNIT_SQUARE = """
			{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}
			""";

	private static final String UNIT_CUT = """
			{"type":"LineString","coordinates":[[0.5,-1],[0.5,2]]}
			""";

	/** Nowhere near any fixture of this file. */
	private static final String CUT_ELSEWHERE = """
			{"type":"LineString","coordinates":[[8.5,52.0],[8.6,52.1]]}
			""";

	private static final String DIAGONAL = """
			{"type":"LineString","coordinates":[[9.98,53.54],[9.99,53.55]]}
			""";

	/** Crosses {@link #DIAGONAL} in its middle. */
	private static final String COUNTER_DIAGONAL = """
			{"type":"LineString","coordinates":[[9.99,53.54],[9.98,53.55]]}
			""";

	private static final String POINT = """
			{"type":"Point","coordinates":[9.985,53.545]}
			""";

	@Autowired
	private SplitMergeService service;

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

	@BeforeEach
	void createLayer() {
		project = projectRepository.saveAndFlush(
				new Project("Teilen-Test " + UUID.randomUUID(), null, 25832, "osm"));
		layer = createLayer("MULTIPOLYGON", "MultiPolygon", 25832);
	}

	@AfterEach
	void dropLayer() {
		dropLayer(layer);
		projectRepository.deleteById(project.getId());
	}

	// --- fixtures ---------------------------------------------------------------------

	/** A layer with the two attribute columns every test in this file asserts on. */
	private Layer createLayer(String geometryType, String columnType, int srid) {
		UUID layerId = UUID.randomUUID();
		String tableName = SqlIdentifier.tableName(layerId);
		jdbc.sql("""
				CREATE TABLE %s (
				    fid     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				    geom    geometry(%s, %d) NOT NULL,
				    strasse text,
				    hoehe   double precision
				)
				""".formatted(SqlIdentifier.quoteLayerTable(tableName), columnType, srid)).update();

		Layer created = layerRepository.saveAndFlush(
				new Layer(layerId, project, "Teilbar", tableName, geometryType, srid));
		fieldRepository.saveAndFlush(new LayerField(created, "Straße", "strasse", "text", 0));
		fieldRepository.saveAndFlush(new LayerField(created, "Höhe", "hoehe", "double precision", 1));
		return created;
	}

	private void dropLayer(Layer target) {
		jdbc.sql("DROP TABLE IF EXISTS " + SqlIdentifier.quoteLayerTable(target.getTableName())).update();
		layerRepository.findById(target.getId()).ifPresent(layerRepository::delete);
	}

	/** Builds an extra layer, hands it to {@code body} and drops it afterwards. */
	private void withLayer(String geometryType, String columnType, int srid, Consumer<Layer> body) {
		Layer extra = createLayer(geometryType, columnType, srid);
		try {
			body.accept(extra);
		}
		finally {
			dropLayer(extra);
		}
	}

	private static JsonNode json(String geoJson) {
		return MAPPER.readTree(geoJson);
	}

	private String table(Layer target) {
		return SqlIdentifier.quoteLayerTable(target.getTableName());
	}

	/** Stores one feature the way an import or a drawn edit would: multi, in the layer's CRS. */
	private long insert(Layer target, String geoJson, String strasse, Double hoehe) {
		return jdbc.sql("INSERT INTO " + table(target) + " (geom, strasse, hoehe) VALUES ("
						+ "ST_Multi(ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(:g), 4326), "
						+ target.getSrid() + ")), :s, :h) RETURNING fid")
				.param("g", geoJson)
				.param("s", strasse)
				.param("h", hoehe)
				.query(Long.class)
				.single();
	}

	/** One feature holding both squares at once -- a MULTIPOLYGON of two separate parts. */
	private long insertTwoPartFeature(Layer target) {
		return jdbc.sql("INSERT INTO " + table(target) + " (geom, strasse, hoehe) VALUES ("
						+ "ST_Multi(ST_Transform(ST_Union("
						+ "  ST_SetSRID(ST_GeomFromGeoJSON(:a), 4326),"
						+ "  ST_SetSRID(ST_GeomFromGeoJSON(:b), 4326)), " + target.getSrid()
						+ ")), 'Zweiteilig', 1.0) RETURNING fid")
				.param("a", SQUARE)
				.param("b", SQUARE_FAR)
				.query(Long.class)
				.single();
	}

	private String rowVersion(Layer target, long fid) {
		return jdbc.sql("SELECT xmin::text FROM " + table(target) + " WHERE fid = :fid")
				.param("fid", fid)
				.query(String.class)
				.single();
	}

	private Map<String, Object> row(Layer target, long fid) {
		return jdbc.sql("SELECT fid, strasse, hoehe, GeometryType(geom) AS geometry_type,"
						+ " ST_Area(geom) AS area, ST_XMin(geom) AS xmin,"
						+ " ST_NumGeometries(geom) AS parts, ST_SRID(geom) AS srid"
						+ " FROM " + table(target) + " WHERE fid = :fid")
				.param("fid", fid)
				.query()
				.singleRow();
	}

	private List<Long> fids(Layer target) {
		return jdbc.sql("SELECT fid FROM " + table(target) + " ORDER BY fid")
				.query(Long.class)
				.list();
	}

	private Layer reload(Layer target) {
		return layerRepository.findById(target.getId()).orElseThrow();
	}

	private SplitMergeDtos.SplitRequest cut(String line, String rowVersion) {
		return new SplitMergeDtos.SplitRequest(json(line), rowVersion);
	}

	private SplitMergeDtos.MergeRequest mergeOf(long leadFid, List<Long> fids, Layer target) {
		Map<String, String> versions = new LinkedHashMap<>();
		fids.forEach(fid -> versions.put(String.valueOf(fid), rowVersion(target, fid)));
		return new SplitMergeDtos.MergeRequest(fids, leadFid, versions);
	}

	// --- split ------------------------------------------------------------------------

	@Test
	@DisplayName("a face splits into two, and both parts carry the original's attributes")
	void splitsAFaceInTwo() {
		long fid = insert(layer, SQUARE, "Alte Gasse", 12.5);

		SplitMergeDtos.SplitResponse response =
				service.split(layer.getId(), fid, cut(CUT_OFF_CENTRE, rowVersion(layer, fid)), null);

		assertThat(response.fids()).hasSize(2);
		assertThat(response.fids().get(0))
				.as("the original keeps its fid, so a selection or an open form stays valid")
				.isEqualTo(fid);
		assertThat(fids(layer)).containsExactlyInAnyOrderElementsOf(response.fids());

		for (long partFid : response.fids()) {
			Map<String, Object> part = row(layer, partFid);
			assertThat(part.get("strasse")).as("fid %d", partFid).isEqualTo("Alte Gasse");
			assertThat(part.get("hoehe")).as("fid %d", partFid).isEqualTo(12.5);
			assertThat(part.get("geometry_type")).isEqualTo("MULTIPOLYGON");
			assertThat(part.get("srid")).isEqualTo(25832);
		}
	}

	@Test
	@DisplayName("the two parts together are the original, and neither overlaps the other")
	void keepsTheWholeShapeAcrossTheParts() {
		long fid = insert(layer, SQUARE, null, null);
		double areaBefore = ((Number) row(layer, fid).get("area")).doubleValue();

		SplitMergeDtos.SplitResponse response =
				service.split(layer.getId(), fid, cut(CUT_OFF_CENTRE, null), null);

		double areaAfter = response.fids().stream()
				.mapToDouble(partFid -> ((Number) row(layer, partFid).get("area")).doubleValue())
				.sum();
		assertThat(areaAfter).isCloseTo(areaBefore, withinPercentage(1e-6));
	}

	@Test
	@DisplayName("the original keeps the larger part -- the piece a user still recognises as it")
	void theOriginalKeepsTheLargerPart() {
		// CUT_OFF_CENTRE takes a quarter off the western edge, so the parts differ clearly
		// in size and the chosen order is decided by size, not by chance.
		long fid = insert(layer, SQUARE, null, null);

		SplitMergeDtos.SplitResponse response =
				service.split(layer.getId(), fid, cut(CUT_OFF_CENTRE, null), null);

		long newFid = response.fids().get(1);
		double originalArea = ((Number) row(layer, fid).get("area")).doubleValue();
		double newArea = ((Number) row(layer, newFid).get("area")).doubleValue();

		assertThat(originalArea).isGreaterThan(newArea);
		assertThat(((Number) row(layer, fid).get("xmin")).doubleValue())
				.as("the larger part here is the eastern one")
				.isGreaterThan(((Number) row(layer, newFid).get("xmin")).doubleValue());
	}

	@Test
	@DisplayName("parts of equal size are ordered west before east, so a symmetric cut is decided too")
	void breaksATieByPosition() {
		// Stored in 4326 so the layer's CRS is the wire CRS and ST_Transform is a no-op --
		// reprojecting into UTM would leave a difference in the last digits, and a
		// tie-break that never triggers is a tie-break nobody has tested.
		withLayer("MULTIPOLYGON", "MultiPolygon", 4326, extra -> {
			long fid = insert(extra, UNIT_SQUARE, null, null);

			SplitMergeDtos.SplitResponse response =
					service.split(extra.getId(), fid, cut(UNIT_CUT, null), null);

			long newFid = response.fids().get(1);
			double originalArea = ((Number) row(extra, fid).get("area")).doubleValue();
			double newArea = ((Number) row(extra, newFid).get("area")).doubleValue();
			assertThat(originalArea).isEqualTo(newArea);

			assertThat(((Number) row(extra, fid).get("xmin")).doubleValue())
					.as("west before east")
					.isLessThan(((Number) row(extra, newFid).get("xmin")).doubleValue());
		});
	}

	@Test
	@DisplayName("a line splits too")
	void splitsALine() {
		withLayer("MULTILINESTRING", "MultiLineString", 25832, lines -> {
			long fid = insert(lines, DIAGONAL, "Weg", 1.0);

			SplitMergeDtos.SplitResponse response =
					service.split(lines.getId(), fid, cut(COUNTER_DIAGONAL, rowVersion(lines, fid)), null);

			assertThat(response.fids()).hasSize(2);
			for (long partFid : response.fids()) {
				assertThat(row(lines, partFid).get("geometry_type")).isEqualTo("MULTILINESTRING");
				assertThat(row(lines, partFid).get("strasse")).isEqualTo("Weg");
			}
		});
	}

	@Test
	@DisplayName("a line beside the object is refused, and nothing is written")
	void refusesALineThatMissesTheObject() {
		long fid = insert(layer, SQUARE, "Bleibt", 3.0);
		long versionBefore = reload(layer).getDataVersion();

		assertThatThrownBy(() -> service.split(layer.getId(), fid, cut(CUT_ELSEWHERE, null), null))
				.isInstanceOf(BadRequestException.class)
				.hasMessage("Die Linie teilt das Objekt nicht.");

		assertThat(fids(layer)).containsExactly(fid);
		assertThat(reload(layer).getDataVersion()).isEqualTo(versionBefore);
	}

	@Test
	@DisplayName("a feature that already has two parts is not 'split' by a line that misses both")
	void countsPartsAgainstWhatTheFeatureAlreadyHad() {
		// ST_Split answers with a collection whether it cut anything or not. For a feature
		// of two separate polygons that collection has two members even when the blade
		// touches neither -- so counting the parts alone would report a split that never
		// happened, and would quietly turn one feature into two.
		long fid = insertTwoPartFeature(layer);
		assertThat(row(layer, fid).get("parts")).isEqualTo(2);

		assertThatThrownBy(() -> service.split(layer.getId(), fid, cut(CUT_ELSEWHERE, null), null))
				.isInstanceOf(BadRequestException.class)
				.hasMessage("Die Linie teilt das Objekt nicht.");

		assertThat(fids(layer)).containsExactly(fid);
	}

	@Test
	@DisplayName("a two-part feature does split when the line really cuts one of its parts")
	void splitsOnePartOfATwoPartFeature() {
		long fid = insertTwoPartFeature(layer);

		SplitMergeDtos.SplitResponse response =
				service.split(layer.getId(), fid, cut(CUT_OFF_CENTRE, null), null);

		assertThat(response.fids())
				.as("two pieces of the cut square plus the untouched far square")
				.hasSize(3);
		assertThat(fids(layer)).hasSize(3);
	}

	@Test
	@DisplayName("a point cannot be split, whatever the column says")
	void refusesToSplitAPoint() {
		withLayer("MULTIPOINT", "MultiPoint", 25832, points -> {
			long fid = insert(points, POINT, null, null);

			assertThatThrownBy(() -> service.split(points.getId(), fid, cut(DIAGONAL, null), null))
					.isInstanceOf(BadRequestException.class)
					.hasMessage("Punkte lassen sich nicht teilen.");
		});
	}

	@Test
	@DisplayName("on a GEOMETRY layer the point check reads the feature, not the column type")
	void refusesToSplitAPointOnAMixedLayer() {
		withLayer("GEOMETRY", "Geometry", 25832, mixed -> {
			long pointFid = insert(mixed, POINT, null, null);
			long faceFid = insert(mixed, SQUARE, null, null);

			assertThatThrownBy(() -> service.split(mixed.getId(), pointFid, cut(DIAGONAL, null), null))
					.isInstanceOf(BadRequestException.class)
					.hasMessage("Punkte lassen sich nicht teilen.");

			assertThat(service.split(mixed.getId(), faceFid, cut(CUT_OFF_CENTRE, null), null).fids())
					.as("the face on the very same layer still splits")
					.hasSize(2);
		});
	}

	@Test
	@DisplayName("a stale row version is a conflict, and nothing is written")
	void refusesToSplitOnAStaleRowVersion() {
		long fid = insert(layer, SQUARE, "Erst", 1.0);

		assertThatThrownBy(() -> service.split(layer.getId(), fid, cut(CUT_OFF_CENTRE, "1"), null))
				.isInstanceOf(ConflictException.class)
				.satisfies(thrown -> assertThat(((ConflictException) thrown).getCurrent())
						.as("the UI has to be able to show what it would overwrite")
						.containsEntry("row_version", rowVersion(layer, fid)));

		assertThat(fids(layer)).containsExactly(fid);
	}

	@Test
	@DisplayName("the current row version is accepted")
	void acceptsTheCurrentRowVersion() {
		long fid = insert(layer, SQUARE, null, null);

		assertThat(service.split(layer.getId(), fid, cut(CUT_OFF_CENTRE, rowVersion(layer, fid)), null).fids())
				.hasSize(2);
	}

	@Test
	void reportsASplitOfAFeatureThatIsGone() {
		assertThatThrownBy(() -> service.split(layer.getId(), 999_999, cut(CUT_OFF_CENTRE, null), null))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	@DisplayName("a blade that is not a line is named, not left to a database error")
	void refusesABladeThatIsNotALine() {
		long fid = insert(layer, SQUARE, null, null);

		assertThatThrownBy(() -> service.split(layer.getId(), fid, cut(POINT, null), null))
				.isInstanceOf(BadRequestException.class)
				.hasMessage("Die Teilungslinie muss eine Linie sein.");
	}

	@Test
	void refusesAMalformedBlade() {
		long fid = insert(layer, SQUARE, null, null);

		assertThatThrownBy(() -> service.split(layer.getId(), fid,
				cut("{\"type\":\"Nonsense\",\"coordinates\":[1,2]}", null), null))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("Teilungslinie");
	}

	@Test
	@DisplayName("the layer's bookkeeping follows a split")
	void updatesLayerStateAfterASplit() {
		long fid = insert(layer, SQUARE, null, null);
		long versionBefore = reload(layer).getDataVersion();

		SplitMergeDtos.SplitResponse response =
				service.split(layer.getId(), fid, cut(CUT_OFF_CENTRE, null), null);

		Layer after = reload(layer);
		assertThat(after.getFeatureCount()).isEqualTo(2);
		assertThat(after.getDataVersion())
				.as("the tile URL is built from this; without a bump the map keeps the old tiles")
				.isGreaterThan(versionBefore);
		assertThat(response.dataVersion()).isEqualTo(after.getDataVersion());
		assertThat(response.featureCount())
				.as("the write computed this; a client must not have to re-read the catalog for it")
				.isEqualTo(after.getFeatureCount());
		assertThat(after.getExtent()).isNotNull();
	}

	// --- merge ------------------------------------------------------------------------

	@Test
	@DisplayName("three faces become one, and the lead keeps its fid and its attributes")
	void mergesThreeFaces() {
		long lead = insert(layer, SQUARE, "Führend", 7.5);
		long second = insert(layer, SQUARE_EAST, "Zweit", 1.0);
		long third = insert(layer, SQUARE_FAR, "Dritt", 2.0);

		SplitMergeDtos.MergeResponse response =
				service.merge(layer.getId(), mergeOf(lead, List.of(lead, second, third), layer), null);

		assertThat(response.fid()).isEqualTo(lead);
		assertThat(fids(layer)).containsExactly(lead);

		Map<String, Object> merged = row(layer, lead);
		assertThat(merged.get("strasse")).as("the lead decides the attributes").isEqualTo("Führend");
		assertThat(merged.get("hoehe")).isEqualTo(7.5);
		assertThat(merged.get("geometry_type")).isEqualTo("MULTIPOLYGON");
	}

	@Test
	@DisplayName("adjacent faces melt into one part, separate ones stay two")
	void unionsRatherThanCollects() {
		long lead = insert(layer, SQUARE, "Führend", null);
		long adjacent = insert(layer, SQUARE_EAST, "Nachbar", null);

		service.merge(layer.getId(), mergeOf(lead, List.of(lead, adjacent), layer), null);
		assertThat(row(layer, lead).get("parts"))
				.as("two faces sharing an edge are one face, not two in a collection")
				.isEqualTo(1);

		long far = insert(layer, SQUARE_FAR, "Fern", null);
		service.merge(layer.getId(), mergeOf(lead, List.of(lead, far), layer), null);

		Map<String, Object> merged = row(layer, lead);
		assertThat(merged.get("geometry_type")).isEqualTo("MULTIPOLYGON");
		assertThat(merged.get("parts"))
				.as("parts need not touch -- two separate faces legitimately become one MULTIPOLYGON")
				.isEqualTo(2);
	}

	@Test
	@DisplayName("the merged shape is the sum of its parts")
	void keepsTheWholeAreaOnMerge() {
		long lead = insert(layer, SQUARE, null, null);
		long far = insert(layer, SQUARE_FAR, null, null);
		double expected = ((Number) row(layer, lead).get("area")).doubleValue()
				+ ((Number) row(layer, far).get("area")).doubleValue();

		service.merge(layer.getId(), mergeOf(lead, List.of(lead, far), layer), null);

		assertThat(((Number) row(layer, lead).get("area")).doubleValue())
				.isCloseTo(expected, withinPercentage(1e-6));
	}

	@Test
	@DisplayName("lines merge into one MULTILINESTRING")
	void mergesLines() {
		withLayer("MULTILINESTRING", "MultiLineString", 25832, lines -> {
			long lead = insert(lines, DIAGONAL, "Weg", null);
			long other = insert(lines, COUNTER_DIAGONAL, "Pfad", null);

			service.merge(lines.getId(), mergeOf(lead, List.of(lead, other), lines), null);

			assertThat(fids(lines)).containsExactly(lead);
			assertThat(row(lines, lead).get("geometry_type")).isEqualTo("MULTILINESTRING");
			assertThat(row(lines, lead).get("strasse")).isEqualTo("Weg");
		});
	}

	@Test
	@DisplayName("a lead outside the selection is refused")
	void refusesALeadOutsideTheSelection() {
		long first = insert(layer, SQUARE, null, null);
		long second = insert(layer, SQUARE_EAST, null, null);

		assertThatThrownBy(() -> service.merge(layer.getId(),
				new SplitMergeDtos.MergeRequest(List.of(first, second), 999_999L, Map.of()), null))
				.isInstanceOf(BadRequestException.class)
				.hasMessage("Das führende Objekt gehört nicht zur Auswahl.");

		assertThat(fids(layer)).containsExactly(first, second);
	}

	@Test
	@DisplayName("mixed geometry kinds are refused")
	void refusesMixedGeometryKinds() {
		withLayer("GEOMETRY", "Geometry", 25832, mixed -> {
			long face = insert(mixed, SQUARE, null, null);
			long line = insert(mixed, DIAGONAL, null, null);

			assertThatThrownBy(() -> service.merge(mixed.getId(),
					new SplitMergeDtos.MergeRequest(List.of(face, line), face, Map.of()), null))
					.isInstanceOf(BadRequestException.class)
					.hasMessage("Nur Objekte derselben Geometrieart lassen sich zusammenführen.");

			assertThat(fids(mixed)).containsExactly(face, line);
		});
	}

	@Test
	@DisplayName("a point among the parts is named as such, not as a mixture")
	void refusesToMergePoints() {
		withLayer("MULTIPOINT", "MultiPoint", 25832, points -> {
			long first = insert(points, POINT, null, null);
			long second = insert(points, "{\"type\":\"Point\",\"coordinates\":[9.99,53.55]}", null, null);

			assertThatThrownBy(() -> service.merge(points.getId(),
					new SplitMergeDtos.MergeRequest(List.of(first, second), first, Map.of()), null))
					.isInstanceOf(BadRequestException.class)
					.hasMessage("Punkte lassen sich nicht zusammenführen.");
		});
	}

	@Test
	@DisplayName("a point on a mixed layer is refused per feature, not per column")
	void refusesToMergeAPointOnAMixedLayer() {
		withLayer("GEOMETRY", "Geometry", 25832, mixed -> {
			long first = insert(mixed, POINT, null, null);
			long second = insert(mixed, "{\"type\":\"Point\",\"coordinates\":[9.99,53.55]}", null, null);

			assertThatThrownBy(() -> service.merge(mixed.getId(),
					new SplitMergeDtos.MergeRequest(List.of(first, second), first, Map.of()), null))
					.isInstanceOf(BadRequestException.class)
					.hasMessage("Punkte lassen sich nicht zusammenführen.");
		});
	}

	@Test
	@DisplayName("one wrong row version among three is a conflict, and nothing at all is written")
	void writesNothingWhenOneRowVersionIsStale() {
		long lead = insert(layer, SQUARE, "Führend", 7.5);
		long second = insert(layer, SQUARE_EAST, "Zweit", 1.0);
		long third = insert(layer, SQUARE_FAR, "Dritt", 2.0);

		double leadAreaBefore = ((Number) row(layer, lead).get("area")).doubleValue();
		long versionBefore = reload(layer).getDataVersion();
		long countBefore = reload(layer).getFeatureCount();

		Map<String, String> versions = new LinkedHashMap<>();
		versions.put(String.valueOf(lead), rowVersion(layer, lead));
		versions.put(String.valueOf(second), rowVersion(layer, second));
		versions.put(String.valueOf(third), "1");

		assertThatThrownBy(() -> service.merge(layer.getId(),
				new SplitMergeDtos.MergeRequest(List.of(lead, second, third), lead, versions), null))
				.isInstanceOf(ConflictException.class)
				.satisfies(thrown -> assertThat(((ConflictException) thrown).getCurrent())
						.containsEntry("fid", third));

		// The batch is one transaction: a conflict on the third part must not have deleted
		// the second or changed the lead's shape.
		assertThat(fids(layer)).containsExactly(lead, second, third);
		assertThat(((Number) row(layer, lead).get("area")).doubleValue()).isEqualTo(leadAreaBefore);
		assertThat(reload(layer).getDataVersion()).isEqualTo(versionBefore);
		assertThat(reload(layer).getFeatureCount()).isEqualTo(countBefore);
	}

	@Test
	@DisplayName("row versions may be omitted, like everywhere else")
	void mergesWithoutRowVersions() {
		long lead = insert(layer, SQUARE, null, null);
		long other = insert(layer, SQUARE_EAST, null, null);

		assertThat(service.merge(layer.getId(),
				new SplitMergeDtos.MergeRequest(List.of(lead, other), lead, null), null).fid())
				.isEqualTo(lead);
	}

	@Test
	void reportsAMergeOverAFeatureThatIsGone() {
		long lead = insert(layer, SQUARE, null, null);

		assertThatThrownBy(() -> service.merge(layer.getId(),
				new SplitMergeDtos.MergeRequest(List.of(lead, 999_999L), lead, Map.of()), null))
				.isInstanceOf(NotFoundException.class)
				.hasMessageContaining("999999");
	}

	@Test
	@DisplayName("fewer than two features are not a merge")
	void refusesTooFewParts() {
		long lead = insert(layer, SQUARE, null, null);

		assertThatThrownBy(() -> service.merge(layer.getId(),
				new SplitMergeDtos.MergeRequest(List.of(lead), lead, Map.of()), null))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("mindestens zwei");

		assertThatThrownBy(() -> service.merge(layer.getId(),
				new SplitMergeDtos.MergeRequest(List.of(lead, lead), lead, Map.of()), null))
				.as("naming the same feature twice is still one feature")
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("mindestens zwei");
	}

	@Test
	@DisplayName("more than a hundred parts are refused before anything is read")
	void refusesTooManyParts() {
		List<Long> tooMany = new ArrayList<>();
		for (long fid = 1; fid <= 101; fid++) {
			tooMany.add(fid);
		}

		assertThatThrownBy(() -> service.merge(layer.getId(),
				new SplitMergeDtos.MergeRequest(tooMany, 1L, Map.of()), null))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("101");
	}

	@Test
	@DisplayName("the layer's bookkeeping follows a merge")
	void updatesLayerStateAfterAMerge() {
		long lead = insert(layer, SQUARE, null, null);
		long other = insert(layer, SQUARE_FAR, null, null);
		long versionBefore = reload(layer).getDataVersion();

		SplitMergeDtos.MergeResponse response =
				service.merge(layer.getId(), mergeOf(lead, List.of(lead, other), layer), null);

		Layer after = reload(layer);
		assertThat(after.getFeatureCount()).isEqualTo(1);
		assertThat(after.getDataVersion()).isGreaterThan(versionBefore);
		assertThat(response.dataVersion()).isEqualTo(after.getDataVersion());
		assertThat(response.featureCount())
				.as("two features went in, one came out -- the answer says so")
				.isEqualTo(1);
		assertThat(after.getExtent()).isNotNull();
	}

	@Test
	void reportsAnUnknownLayer() {
		UUID unknown = UUID.randomUUID();

		assertThatThrownBy(() -> service.split(unknown, 1, cut(DIAGONAL, null), null))
				.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.merge(unknown,
				new SplitMergeDtos.MergeRequest(List.of(1L, 2L), 1L, Map.of()), null))
				.isInstanceOf(NotFoundException.class);
	}
}
