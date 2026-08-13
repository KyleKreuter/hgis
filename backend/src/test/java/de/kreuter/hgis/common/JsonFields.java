package de.kreuter.hgis.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asserts the complete set of field names a response object carries.
 *
 * <p>Field-by-field assertions ({@code jsonPath("$.description")} and the like) only see
 * the fields somebody thought to name. A field that is renamed, dropped or added stays
 * invisible to them: the renamed one is simply never asked for, and every existing
 * assertion keeps passing while the frontend reads {@code undefined}. Comparing the
 * whole set instead makes the response shape itself the thing under test, so any change
 * to it has to be made deliberately, here as well as in the DTO.
 *
 * <p>Deliberately about names only, not values -- what the values must be is what the
 * surrounding tests already state. This is the contract on top of them.
 *
 * <p>Null-valued fields are part of the set: nothing in this application configures
 * Jackson to leave them out, so the response carries a {@code "description": null} and
 * the expected set must name it. A DTO that does opt out per field ({@code @JsonInclude})
 * needs its expected set built from a fixture that fills those fields.
 */
public final class JsonFields {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private JsonFields() {
	}

	/**
	 * The response body parsed as a tree. Read as UTF-8, the charset every endpoint sends.
	 *
	 * <p>The declared {@code UnsupportedEncodingException} cannot happen -- UTF-8 is
	 * required of every JVM -- so it is turned into an unchecked one here rather than
	 * pushed onto every caller.
	 */
	public static JsonNode tree(MvcResult result) {
		try {
			return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
		}
		catch (UnsupportedEncodingException impossible) {
			throw new IllegalStateException("UTF-8 is not available", impossible);
		}
	}

	/** Field names of one object node, in document order. */
	public static Set<String> namesOf(JsonNode node) {
		assertThat(node).as("the node to read field names from").isNotNull();
		assertThat(node.isObject()).as("field names need an object node, got: %s", node.getNodeType()).isTrue();
		return new LinkedHashSet<>(node.propertyNames());
	}

	/**
	 * Asserts that {@code node} carries exactly {@code expected} and nothing else.
	 *
	 * @param what names the object in the failure message, e.g. {@code "JobDtos.Response"}
	 */
	public static void assertFieldNames(JsonNode node, String what, String... expected) {
		assertThat(namesOf(node))
				.as("%s: the response shape changed -- update the DTO's consumers, then this list", what)
				.containsExactlyInAnyOrder(expected);
	}
}
