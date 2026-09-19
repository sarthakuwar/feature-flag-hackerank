package com.featureflag.flags.experiment;

import static com.featureflag.flags.rollout.RolloutFixtures.RULE_ID;
import static com.featureflag.flags.rollout.RolloutFixtures.T0;
import static com.featureflag.flags.rollout.RolloutFixtures.ended;
import static com.featureflag.flags.rollout.RolloutFixtures.flagWithRule;
import static com.featureflag.flags.rollout.RolloutFixtures.production;
import static com.featureflag.flags.rollout.RolloutFixtures.rollout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagAccess;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagStreamService;
import com.featureflag.flags.RolloutStatus;
import com.featureflag.flags.StrategyType;
import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.metrics.MetricsService;
import com.featureflag.shared.ApiException;

/** The reverse of the start-time guard: an experiment cannot be introduced next to an active progressive rollout. */
@ExtendWith(MockitoExtension.class)
class ExperimentServiceProgressiveRolloutTest {

	private static final Map<String, Integer> WEIGHTS = Map.of("off", 34, "on", 33, "beta", 33);

	@Mock
	private FlagRepository flagRepository;

	@Mock
	private FlagCacheService flagCacheService;

	@Mock
	private FlagStreamService flagStreamService;

	@Mock
	private AuditService auditService;

	@Mock
	private MetricsService metricsService;

	private ExperimentService experimentService;

	@BeforeEach
	void setUp() {
		experimentService = new ExperimentService(flagRepository, flagCacheService, flagStreamService, auditService,
				metricsService, new EpsilonGreedyAllocator(), new ThompsonSamplingAllocator(), new FlagAccess(flagRepository));
		lenient().when(flagRepository.save(any(Flag.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private Flag flagWithActiveRollout() {
		Flag flag = flagWithRule("f");
		production(flag).getProgressiveRollouts().add(rollout("r1", RULE_ID, "off", "on", T0));
		when(flagRepository.findByKey("f")).thenReturn(Optional.of(flag));

		return flag;
	}

	@Test
	void settingWeightsIsRefusedWhileARolloutIsActive() {
		Flag flag = flagWithActiveRollout();

		assertThatThrownBy(() -> experimentService.setVariationWeights("f", EnvironmentKey.PRODUCTION, WEIGHTS, "tester"))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.getStatusCode()).isEqualTo(409);
					assertThat(exception.getCode()).isEqualTo("ROLLOUT_IN_PROGRESS");
				});
		verify(flagRepository, never()).save(any());
		assertThat(production(flag).hasExperiment()).isFalse();
	}

	@Test
	void promotingAWinnerIsRefusedWhileARolloutIsActive() {
		Flag flag = flagWithActiveRollout();

		assertThatThrownBy(() -> experimentService.promoteToFullRollout("f", EnvironmentKey.PRODUCTION, "on", "tester"))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo("ROLLOUT_IN_PROGRESS"));
		verify(flagRepository, never()).save(any());
		assertThat(production(flag).hasExperiment()).isFalse();
	}

	@Test
	void anExperimentCanStartOnceTheRolloutHasEnded() {
		Flag flag = flagWithRule("f");
		production(flag).getProgressiveRollouts().add(ended(rollout("r1", RULE_ID, "off", "on", T0), RolloutStatus.STOPPED, "off", 25));
		when(flagRepository.findByKey("f")).thenReturn(Optional.of(flag));

		Flag saved = experimentService.setVariationWeights("f", EnvironmentKey.PRODUCTION, WEIGHTS, "tester");

		assertThat(production(saved).getStrategies()).anyMatch(strategy -> strategy.getType() == StrategyType.EXPERIMENT_SPLIT);
	}

	@Test
	void aRolloutInAnotherEnvironmentDoesNotBlockAnExperimentHere() {
		Flag flag = flagWithRule("f");
		flag.getEnvironments().get(EnvironmentKey.STAGING).getProgressiveRollouts().add(rollout("r1", RULE_ID, "off", "on", T0));
		when(flagRepository.findByKey("f")).thenReturn(Optional.of(flag));

		Flag saved = experimentService.setVariationWeights("f", EnvironmentKey.PRODUCTION, WEIGHTS, "tester");

		assertThat(production(saved).hasExperiment()).isTrue();
	}
}
