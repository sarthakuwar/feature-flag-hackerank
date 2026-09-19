package com.featureflag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeatureFlagApplicationTest {

	@Test
	void theApplicationEnablesGracefulShutdownByDefault() {
		assertThat(FeatureFlagApplication.defaultProperties()).containsEntry("server.shutdown", "graceful")
				.containsEntry("spring.lifecycle.timeout-per-shutdown-phase", "20s");
	}
}
