package de.kreuter.hgis.jobs;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One safe codec for the small, type-specific JSONB payload carried by a job.
 *
 * <p>The mapper is the one Spring configured, not a private instance: a job's parameters
 * are read back by the same application that wrote them, and a second mapper with its own
 * defaults is a second set of rules for the same column.
 */
@Component
public class JobParameters {

	private final ObjectMapper objectMapper;

	JobParameters(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String duplicate(UUID outputProjectId) {
		return objectMapper.writeValueAsString(Map.of("outputProjectId", outputProjectId));
	}

	/** Invalid legacy/corrupt parameters are not allowed to break polling or recovery. */
	public UUID outputProjectId(String parameters) {
		if (parameters == null || parameters.isBlank()) return null;
		try {
			JsonNode node = objectMapper.readTree(parameters);
			if (node == null || !node.hasNonNull("outputProjectId")) return null;
			return UUID.fromString(node.get("outputProjectId").asString());
		} catch (JacksonException | IllegalArgumentException ignored) {
			return null;
		}
	}
}
