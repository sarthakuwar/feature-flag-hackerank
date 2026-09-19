package com.featureflag.flags.rollout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProgressiveRolloutSchedulerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withBean(ProgressiveRolloutService.class, () -> mock(ProgressiveRolloutService.class))
			.withUserConfiguration(ProgressiveRolloutScheduler.class);

	@Test
	void aTickDelegatesToTheService() {
		ProgressiveRolloutService service = mock(ProgressiveRolloutService.class);

		new ProgressiveRolloutScheduler(service).completeDueRollouts();

		verify(service).completeDueRollouts();
	}

	@Test
	void theSchedulerIsOnByDefault() {
		contextRunner.run(context -> assertThat(context).hasSingleBean(ProgressiveRolloutScheduler.class));
	}

	@Test
	void theSchedulerCanBeSwitchedOffByProperty() {
		contextRunner.withPropertyValues("featureflag.rollout.scheduler.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean(ProgressiveRolloutScheduler.class));
	}
}
