package de.kreuter.hgis.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JobParametersTest {

	private final JobParameters parameters = new JobParameters(JsonMapper.builder().build());

	@Test
	void roundTripsDuplicateOutputProjectId() {
		UUID id = UUID.randomUUID();
		assertThat(parameters.outputProjectId(parameters.duplicate(id))).isEqualTo(id);
	}

	@Test
	void invalidParametersNeverBreakJobPollingOrRecovery() {
		assertThat(parameters.outputProjectId("{not json")).isNull();
		assertThat(parameters.outputProjectId("{\"outputProjectId\":\"not-a-uuid\"}")).isNull();
		assertThat(parameters.outputProjectId("{\"outputProjectId\":{\"id\":1}}")).isNull();
		assertThat(parameters.outputProjectId("{\"outputProjectId\":null}")).isNull();
		assertThat(parameters.outputProjectId("{}")).isNull();
		assertThat(parameters.outputProjectId("   ")).isNull();
		assertThat(parameters.outputProjectId(null)).isNull();
	}
}
