package de.kreuter.hgis.features;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.kreuter.hgis.catalog.LayerField;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the evaluation set in `ai/eval` parsable.
 *
 * <p>That set is the target of the natural-language model in `ai/`: every sentence has a
 * hand-written filter expression next to it, and the model is scored on reproducing it.
 * An expression this parser rejects would score the model against something the product
 * cannot run, so each one is parsed here.
 *
 * <p>This also works the other way round. A change to the grammar that drops a shape the
 * set relies on fails here, before it silently invalidates the training data built from
 * the same shapes.
 */
class GoldFilterExpressionsTest {

	private static final Path EVAL = Path.of("..", "ai", "eval");
	private static final ObjectMapper JSON = new ObjectMapper();

	/** One line of `gold.jsonl`. */
	private record GoldEntry(String id, String schema, String sentence, String expression) {
		@Override
		public String toString() {
			// What a failed parameterised case prints -- id and expression, not the record.
			return id + ": " + expression;
		}
	}

	private static Map<String, List<LayerField>> schemas() throws IOException {
		JsonNode root = JSON.readTree(Files.readString(EVAL.resolve("schemas.json"), StandardCharsets.UTF_8));
		Map<String, List<LayerField>> schemas = new HashMap<>();
		root.properties().forEach(schema -> {
			List<LayerField> fields = new ArrayList<>();
			int ordinal = 0;
			for (JsonNode field : schema.getValue().get("fields")) {
				String name = field.get("name").asText();
				// The evaluation set names fields the way a user sees them. The column
				// name never appears in an expression, so a normalised copy is enough.
				fields.add(new LayerField(null, name, name.toLowerCase().replaceAll("[^a-z0-9]+", "_"),
						field.get("type").asText(), ordinal++));
			}
			schemas.put(schema.getKey(), fields);
		});
		return schemas;
	}

	private static List<GoldEntry> entries() throws IOException {
		List<GoldEntry> entries = new ArrayList<>();
		for (String line : Files.readAllLines(EVAL.resolve("gold.jsonl"), StandardCharsets.UTF_8)) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode node = JSON.readTree(line);
			entries.add(new GoldEntry(node.get("id").asText(), node.get("schema").asText(),
					node.get("sentence").asText(), node.get("expression").asText()));
		}
		return entries;
	}

	@Test
	@DisplayName("every gold expression parses against its schema")
	void everyGoldExpressionParses() throws IOException {
		Map<String, List<LayerField>> schemas = schemas();
		List<String> rejected = new ArrayList<>();

		for (GoldEntry entry : entries()) {
			List<LayerField> fields = schemas.get(entry.schema());
			assertThat(fields).as("schema %s is defined", entry.schema()).isNotNull();
			try {
				assertThat(FilterParser.parse(entry.expression(), fields)).isNotNull();
			}
			catch (RuntimeException ex) {
				// Collected rather than thrown: one run should list every bad line, not
				// stop at the first. Fixing them one test run at a time costs an hour.
				rejected.add(entry.id() + "  " + entry.expression() + "\n      " + ex.getMessage());
			}
		}

		assertThat(rejected).as("expressions the parser rejects").isEmpty();
	}

	/**
	 * The training data is generated, and its own generator only checks the shape of an
	 * expression against the grammar it emits. This runs a versioned sample of it through
	 * the parser that actually serves the query, which is the only authority on what the
	 * product accepts.
	 */
	@Test
	@DisplayName("every expression in the training sample parses")
	void everyTrainingSampleExpressionParses() throws IOException {
		List<String> rejected = new ArrayList<>();

		for (String line : Files.readAllLines(EVAL.resolve("train-sample.jsonl"), StandardCharsets.UTF_8)) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode node = JSON.readTree(line);
			// The sample carries its own field list: its schemas are generated and live
			// in `ai/generator`, not in `schemas.json`.
			List<LayerField> fields = new ArrayList<>();
			int ordinal = 0;
			for (JsonNode field : node.get("fields")) {
				String name = field.get("name").asText();
				fields.add(new LayerField(null, name, name.toLowerCase().replaceAll("[^a-z0-9]+", "_"),
						field.get("type").asText(), ordinal++));
			}
			String expression = node.get("expression").asText();
			try {
				assertThat(FilterParser.parse(expression, fields)).isNotNull();
			}
			catch (RuntimeException ex) {
				rejected.add(expression + "\n      " + ex.getMessage());
			}
		}

		assertThat(rejected).as("training expressions the parser rejects").isEmpty();
	}

	@Test
	@DisplayName("the set stays large enough and spread over every schema")
	void theSetCoversEverySchema() throws IOException {
		List<GoldEntry> entries = entries();

		assertThat(entries).hasSizeGreaterThanOrEqualTo(300);
		assertThat(entries).extracting(GoldEntry::id).doesNotHaveDuplicates();
		assertThat(entries).extracting(GoldEntry::sentence).doesNotHaveDuplicates();
		assertThat(entries).extracting(GoldEntry::schema)
				.containsAll(schemas().keySet());
	}
}
