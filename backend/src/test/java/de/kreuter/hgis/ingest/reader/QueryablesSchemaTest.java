package de.kreuter.hgis.ingest.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The title resolution and type mapping CONTRACT.md 11.4 and phase 23's plan (section 6.3)
 * describe, checked against a queryables document shaped like the tree cadastre's real one
 * (measured live -- see the phase 23 backend report).
 */
class QueryablesSchemaTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	@DisplayName("a German label from the service directory wins over the schema's own title")
	void germanLabelWinsOverSchemaTitle() {
		JsonNode schema = readSchema("""
				{"properties": {
				  "kronendurchmesser_z": {"title": "kronendurchmesser_z", "type": "string"}
				}}""");

		List<QueryablesSchema.Field> fields =
				QueryablesSchema.parse(schema, Map.of("kronendurchmesser_z", "Kronendurchmesser"));

		assertThat(fields).hasSize(1);
		assertThat(fields.get(0).title()).isEqualTo("Kronendurchmesser");
	}

	@Test
	@DisplayName("without a German label, a schema title that differs from the technical name is used")
	void schemaTitleUsedWhenItDiffersFromTechnicalName() {
		JsonNode schema = readSchema("""
				{"properties": {
				  "baumid": {"title": "BaumID", "type": "integer"}
				}}""");

		List<QueryablesSchema.Field> fields = QueryablesSchema.parse(schema, Map.of());

		assertThat(fields.get(0).title()).isEqualTo("BaumID");
	}

	@Test
	@DisplayName("without either, the technical name is the title -- exactly the 'gid' case measured live")
	void technicalNameIsTheFallback() {
		JsonNode schema = readSchema("""
				{"properties": {
				  "gid": {"title": "gid", "type": "integer", "readOnly": true, "x-ogc-role": "id"}
				}}""");

		List<QueryablesSchema.Field> fields = QueryablesSchema.parse(schema, Map.of());

		assertThat(fields.get(0).title()).isEqualTo("gid");
		assertThat(fields.get(0).idField()).isTrue();
	}

	@Test
	@DisplayName("a title collision keeps the first field's resolved title and reverts the second to its technical name")
	void secondCollidingFieldRevertsToItsTechnicalName() {
		JsonNode schema = readSchema("""
				{"properties": {
				  "art": {"title": "Baumart", "type": "string"},
				  "art_deutsch": {"title": "Baumart", "type": "string"}
				}}""");

		List<QueryablesSchema.Field> fields = QueryablesSchema.parse(schema, Map.of());

		assertThat(fields.get(0).title()).isEqualTo("Baumart");
		assertThat(fields.get(1).title()).as("second occurrence falls back to its own technical name")
				.isEqualTo("art_deutsch");
	}

	@Test
	@DisplayName("field order is preserved -- declaration order, matching x-ogc-propertySeq on every collection checked live")
	void fieldOrderMatchesDeclarationOrder() {
		JsonNode schema = readSchema("""
				{"properties": {
				  "gid": {"title": "gid", "type": "integer"},
				  "baumid": {"title": "BaumID", "type": "integer"},
				  "gattung": {"title": "Gattung", "type": "string"}
				}}""");

		List<QueryablesSchema.Field> fields = QueryablesSchema.parse(schema, Map.of());

		assertThat(fields).extracting(QueryablesSchema.Field::technicalName)
				.containsExactly("gid", "baumid", "gattung");
	}

	@Test
	@DisplayName("JSON Schema types map to the plan's Java types -- integer is always Long, never Integer")
	void mapsJsonSchemaTypesToJavaTypes() {
		JsonNode schema = readSchema("""
				{"properties": {
				  "a": {"type": "integer"},
				  "b": {"type": "number"},
				  "c": {"type": "boolean"},
				  "d": {"type": "string"},
				  "e": {"type": "string", "format": "date"},
				  "f": {"type": "string", "format": "date-time"},
				  "g": {"type": "array"},
				  "h": {"type": "object"}
				}}""");

		List<QueryablesSchema.Field> fields = QueryablesSchema.parse(schema, Map.of());

		assertThat(fields.get(0).javaType()).isEqualTo(Long.class);
		assertThat(fields.get(1).javaType()).isEqualTo(Double.class);
		assertThat(fields.get(2).javaType()).isEqualTo(Boolean.class);
		assertThat(fields.get(3).javaType()).isEqualTo(String.class);
		assertThat(fields.get(4).javaType()).isEqualTo(Date.class);
		assertThat(fields.get(5).javaType()).isEqualTo(Instant.class);
		assertThat(fields.get(6).javaType()).isEqualTo(String.class);
		assertThat(fields.get(7).javaType()).isEqualTo(String.class);
	}

	@Test
	@DisplayName("enum values are capped at 20 and empty, never null, when there is none")
	void enumValuesAreCappedAndDefaultToEmpty() {
		String manyValues = java.util.stream.IntStream.range(0, 30)
				.mapToObj(i -> "\"v" + i + "\"")
				.reduce((a, b) -> a + "," + b)
				.orElseThrow();
		JsonNode schema = readSchema("""
				{"properties": {
				  "gattung": {"type": "string", "enum": [%s]},
				  "baumid": {"type": "integer"}
				}}""".formatted(manyValues));

		List<QueryablesSchema.Field> fields = QueryablesSchema.parse(schema, Map.of());

		assertThat(fields.get(0).enumValues()).hasSize(QueryablesSchema.MAX_ENUM_VALUES);
		assertThat(fields.get(1).enumValues()).isEmpty();
	}

	@Test
	@DisplayName("the primary-geometry property is never listed as an attribute field -- it would stay 100% NULL")
	void primaryGeometryPropertyIsExcluded() {
		// Shaped like hvv_einzugsbereiche/haltestellenbereiche_bus, measured live: "geom"
		// carries x-ogc-role "primary-geometry" and no "type" at all, only "format".
		JsonNode schema = readSchema("""
				{"properties": {
				  "id": {"title": "id", "type": "integer", "readOnly": true, "x-ogc-role": "id"},
				  "geom": {"title": "geom", "x-ogc-role": "primary-geometry", "format": "geometry-point"},
				  "name": {"title": "name", "type": "string"}
				}}""");

		List<QueryablesSchema.Field> fields = QueryablesSchema.parse(schema, Map.of());

		assertThat(fields).extracting(QueryablesSchema.Field::technicalName)
				.as("geom is the geometry, not an attribute -- it must not appear here")
				.containsExactly("id", "name");
	}

	@Test
	@DisplayName("a real attribute happens to be named 'geom' is kept -- only the role, not the name, decides")
	void attributeNamedGeomWithoutThePrimaryGeometryRoleIsKept() {
		JsonNode schema = readSchema("""
				{"properties": {
				  "geom": {"title": "geom", "type": "string"}
				}}""");

		List<QueryablesSchema.Field> fields = QueryablesSchema.parse(schema, Map.of());

		assertThat(fields).extracting(QueryablesSchema.Field::technicalName).containsExactly("geom");
	}

	private static JsonNode readSchema(String json) {
		return MAPPER.readTree(json);
	}
}
