package com.featureflag.flags.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import com.featureflag.flags.BanditAlgorithm;
import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagAccess;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagLifecycle;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagStreamService;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.Strategy;
import com.featureflag.flags.StrategyType;
import com.featureflag.flags.Variation;
import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.metrics.MetricsService;
import com.featureflag.flags.metrics.dto.ExperimentMetricsResponse;
import com.featureflag.flags.metrics.dto.VariationMetrics;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.ValidationException;

@ExtendWith(MockitoExtension.class)
class ExperimentServiceTest {

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
		lenient().when(flagRepository.save(any(Flag.class))).thenAnswer((InvocationOnMock invocation) -> invocation.getArgument(0));
	}

	@Test
	void setVariationWeightsRejectsAMapNotMatchingTheFlagsVariations() {
		Flag flag = threeVariationFlag("exp-flag");
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(flag));

		Map<String, Integer> weights = new LinkedHashMap<>();
		weights.put("a", 50);
		weights.put("does-not-exist", 50);

		assertThatThrownBy(() -> experimentService.setVariationWeights("exp-flag", EnvironmentKey.PRODUCTION, weights, "tester"))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void setVariationWeightsRejectsWeightsNotSummingToOneHundred() {
		Flag flag = threeVariationFlag("exp-flag");
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(flag));

		Map<String, Integer> weights = new LinkedHashMap<>();
		weights.put("a", 50);
		weights.put("b", 20);
		weights.put("c", 20);

		assertThatThrownBy(() -> experimentService.setVariationWeights("exp-flag", EnvironmentKey.PRODUCTION, weights, "tester"))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void setVariationWeightsPersistsAValidSplitAsAnExperimentSplitStrategy() {
		Flag flag = threeVariationFlag("exp-flag");
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(flag));

		Map<String, Integer> weights = new LinkedHashMap<>();
		weights.put("a", 50);
		weights.put("b", 25);
		weights.put("c", 25);

		Flag saved = experimentService.setVariationWeights("exp-flag", EnvironmentKey.PRODUCTION, weights, "tester");

		Strategy strategy = experimentStrategy(saved, EnvironmentKey.PRODUCTION);
		assertThat(strategy.getType()).isEqualTo(StrategyType.EXPERIMENT_SPLIT);
		assertThat(strategy.getVariationWeights()).containsExactlyInAnyOrderEntriesOf(weights);
	}

	@Test
	void configureBanditRejectsMissingEpsilonForEpsilonGreedy() {
		Flag flag = threeVariationFlag("exp-flag");
		addExperimentStrategy(flag, EnvironmentKey.PRODUCTION, Map.of("a", 34, "b", 33, "c", 33), BanditAlgorithm.NONE, null);
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> experimentService.configureBandit("exp-flag", EnvironmentKey.PRODUCTION,
				BanditAlgorithm.EPSILON_GREEDY, null, "tester")).isInstanceOf(ValidationException.class);
	}

	@Test
	void configureBanditRejectsEpsilonOutOfRange() {
		Flag flag = threeVariationFlag("exp-flag");
		addExperimentStrategy(flag, EnvironmentKey.PRODUCTION, Map.of("a", 34, "b", 33, "c", 33), BanditAlgorithm.NONE, null);
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> experimentService.configureBandit("exp-flag", EnvironmentKey.PRODUCTION,
				BanditAlgorithm.EPSILON_GREEDY, 1.5, "tester")).isInstanceOf(ValidationException.class);
	}

	@Test
	void configureBanditRequiresAnExperimentSplitStrategyToAlreadyExist() {
		Flag flag = threeVariationFlag("exp-flag");
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> experimentService.configureBandit("exp-flag", EnvironmentKey.PRODUCTION,
				BanditAlgorithm.THOMPSON_SAMPLING, null, "tester")).isInstanceOf(ValidationException.class);
	}

	@Test
	void reallocateNowThrowsConflictWhenNoBanditIsConfigured() {
		Flag flag = threeVariationFlag("exp-flag");
		addExperimentStrategy(flag, EnvironmentKey.PRODUCTION, Map.of("a", 34, "b", 33, "c", 33), BanditAlgorithm.NONE, null);
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> experimentService.reallocateNow("exp-flag", EnvironmentKey.PRODUCTION, "tester"))
				.isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "BANDIT_NOT_CONFIGURED");
	}

	@Test
	void reallocateNowUpdatesWeightsWhenABanditIsConfigured() {
		Flag flag = threeVariationFlag("exp-flag");
		addExperimentStrategy(flag, EnvironmentKey.PRODUCTION, Map.of("a", 34, "b", 33, "c", 33), BanditAlgorithm.EPSILON_GREEDY,
				0.1);
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(flag));
		when(metricsService.metrics("exp-flag", EnvironmentKey.PRODUCTION, null))
				.thenReturn(new ExperimentMetricsResponse("exp-flag", EnvironmentKey.PRODUCTION,
						List.of(new VariationMetrics("a", 100, 5, 0.05, 34), new VariationMetrics("b", 100, 40, 0.40, 33),
								new VariationMetrics("c", 100, 10, 0.10, 33)),
						BanditAlgorithm.EPSILON_GREEDY, 0.1, 0));

		Flag saved = experimentService.reallocateNow("exp-flag", EnvironmentKey.PRODUCTION, "tester");

		Strategy strategy = experimentStrategy(saved, EnvironmentKey.PRODUCTION);
		assertThat(strategy.getVariationWeights().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
		assertThat(strategy.getVariationWeights().get("b")).isGreaterThan(strategy.getVariationWeights().get("a"));
	}

	@Test
	void reallocateIfBanditEnabledIsANoOpWhenNoBanditIsConfigured() {
		Flag flag = threeVariationFlag("exp-flag");
		addExperimentStrategy(flag, EnvironmentKey.PRODUCTION, Map.of("a", 34, "b", 33, "c", 33), BanditAlgorithm.NONE, null);
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(flag));

		experimentService.reallocateIfBanditEnabled("exp-flag", EnvironmentKey.PRODUCTION);

		verify(flagRepository, never()).save(any());
	}

	@Test
	void reallocateIfBanditEnabledShiftsWeightTowardTheVariationThatConverts() {
		Flag flag = banditFlag(BanditAlgorithm.THOMPSON_SAMPLING, null);
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(flag));
		stubMetrics(new VariationMetrics("a", 1000, 10, 0.01, 34), new VariationMetrics("b", 1000, 600, 0.6, 33),
				new VariationMetrics("c", 1000, 20, 0.02, 33));

		experimentService.reallocateIfBanditEnabled("exp-flag", EnvironmentKey.PRODUCTION);

		Map<String, Integer> weights = experimentStrategy(flag, EnvironmentKey.PRODUCTION).getVariationWeights();
		assertThat(weights.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
		assertThat(weights.get("b")).isGreaterThan(90);
		verify(flagRepository).save(flag);
		verify(flagCacheService).put(flag);
		verify(auditService).record(eq("exp-flag"), eq(AuditAction.REALLOCATE), eq("system:bandit"), anyString());
		verify(flagStreamService).publishFlagChanged(EnvironmentKey.PRODUCTION, "exp-flag", AuditAction.REALLOCATE.name());
	}

	@Test
	void reallocateIfBanditEnabledReloadsTheFlagAndRetriesAfterAnOptimisticLockConflict() {
		Flag first = banditFlag(BanditAlgorithm.EPSILON_GREEDY, 0.1);
		Flag second = banditFlag(BanditAlgorithm.EPSILON_GREEDY, 0.1);
		Flag third = banditFlag(BanditAlgorithm.EPSILON_GREEDY, 0.1);
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(first), Optional.of(second), Optional.of(third));
		when(flagRepository.save(any(Flag.class))).thenThrow(new OptimisticLockingFailureException("stale"))
				.thenThrow(new OptimisticLockingFailureException("stale")).thenAnswer(invocation -> invocation.getArgument(0));
		stubMetrics(new VariationMetrics("a", 100, 5, 0.05, 34), new VariationMetrics("b", 100, 40, 0.4, 33),
				new VariationMetrics("c", 100, 10, 0.1, 33));

		experimentService.reallocateIfBanditEnabled("exp-flag", EnvironmentKey.PRODUCTION);

		verify(flagRepository, times(3)).findByKey("exp-flag");
		verify(flagRepository, times(3)).save(any(Flag.class));
		verify(flagCacheService, times(1)).put(third);
		verify(flagStreamService, times(1)).publishFlagChanged(EnvironmentKey.PRODUCTION, "exp-flag", AuditAction.REALLOCATE.name());
		assertThat(experimentStrategy(third, EnvironmentKey.PRODUCTION).getVariationWeights().get("b")).isGreaterThan(34);
	}

	@Test
	void reallocateIfBanditEnabledGivesUpQuietlyAfterThreeRetries() {
		when(flagRepository.findByKey("exp-flag"))
				.thenAnswer(invocation -> Optional.of(banditFlag(BanditAlgorithm.EPSILON_GREEDY, 0.1)));
		when(flagRepository.save(any(Flag.class))).thenThrow(new OptimisticLockingFailureException("stale"));
		stubMetrics(new VariationMetrics("a", 100, 5, 0.05, 34), new VariationMetrics("b", 100, 40, 0.4, 33),
				new VariationMetrics("c", 100, 10, 0.1, 33));

		experimentService.reallocateIfBanditEnabled("exp-flag", EnvironmentKey.PRODUCTION);

		verify(flagRepository, times(4)).save(any(Flag.class));
		verify(flagCacheService, never()).put(any());
		verify(auditService, never()).record(any(), any(), any(), any());
		verify(flagStreamService, never()).publishFlagChanged(any(), any(), any());
	}

	@Test
	void reallocateIfBanditEnabledDoesNotSwallowOtherFailures() {
		Flag flag = banditFlag(BanditAlgorithm.EPSILON_GREEDY, 0.1);
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(flag));
		when(flagRepository.save(any(Flag.class))).thenThrow(new IllegalStateException("mongo is down"));
		stubMetrics(new VariationMetrics("a", 100, 5, 0.05, 34), new VariationMetrics("b", 100, 40, 0.4, 33),
				new VariationMetrics("c", 100, 10, 0.1, 33));

		assertThatThrownBy(() -> experimentService.reallocateIfBanditEnabled("exp-flag", EnvironmentKey.PRODUCTION))
				.isInstanceOf(IllegalStateException.class);
		verify(flagRepository, times(1)).save(any(Flag.class));
	}

	@Test
	void promoteToFullRolloutSetsTheWinnerToOneHundredAndClearsTheBandit() {
		Flag flag = threeVariationFlag("exp-flag");
		addExperimentStrategy(flag, EnvironmentKey.PRODUCTION, Map.of("a", 34, "b", 33, "c", 33), BanditAlgorithm.EPSILON_GREEDY,
				0.1);
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(flag));

		Flag saved = experimentService.promoteToFullRollout("exp-flag", EnvironmentKey.PRODUCTION, "b", "tester");

		Strategy strategy = experimentStrategy(saved, EnvironmentKey.PRODUCTION);
		assertThat(strategy.getVariationWeights().get("b")).isEqualTo(100);
		assertThat(strategy.getVariationWeights().get("a")).isEqualTo(0);
		assertThat(strategy.getVariationWeights().get("c")).isEqualTo(0);
		assertThat(strategy.getBanditAlgorithm()).isEqualTo(BanditAlgorithm.NONE);
		assertThat(saved.getEnvironments().get(EnvironmentKey.PRODUCTION).getOffVariationKey()).isEqualTo("b");
	}

	@Test
	void promoteToFullRolloutRejectsAnUnknownVariationKey() {
		Flag flag = threeVariationFlag("exp-flag");
		when(flagRepository.findByKey("exp-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(
				() -> experimentService.promoteToFullRollout("exp-flag", EnvironmentKey.PRODUCTION, "does-not-exist", "tester"))
				.isInstanceOf(ValidationException.class);
	}

	private Flag banditFlag(BanditAlgorithm algorithm, Double epsilon) {
		Flag flag = threeVariationFlag("exp-flag");
		addExperimentStrategy(flag, EnvironmentKey.PRODUCTION, Map.of("a", 34, "b", 33, "c", 33), algorithm, epsilon);

		return flag;
	}

	private void stubMetrics(VariationMetrics... variations) {
		when(metricsService.metrics("exp-flag", EnvironmentKey.PRODUCTION, null)).thenReturn(new ExperimentMetricsResponse(
				"exp-flag", EnvironmentKey.PRODUCTION, List.of(variations), BanditAlgorithm.NONE, null, 0));
	}

	private Strategy experimentStrategy(Flag flag, EnvironmentKey environment) {
		return flag.getEnvironments().get(environment).getStrategies().stream()
				.filter(strategy -> strategy.getType() == StrategyType.EXPERIMENT_SPLIT).findFirst().orElseThrow();
	}

	private void addExperimentStrategy(Flag flag, EnvironmentKey environment, Map<String, Integer> weights,
			BanditAlgorithm algorithm, Double epsilon) {
		Strategy strategy = new Strategy();
		strategy.setId("experiment-strategy");
		strategy.setType(StrategyType.EXPERIMENT_SPLIT);
		strategy.setVariationWeights(new LinkedHashMap<>(weights));
		strategy.setBanditAlgorithm(algorithm);
		strategy.setEpsilon(epsilon);
		flag.getEnvironments().get(environment).getStrategies().add(strategy);
	}

	private Flag threeVariationFlag(String key) {
		Flag flag = new Flag();
		flag.setKey(key);
		flag.setName("Experiment Flag");
		flag.setType(FlagType.MULTIVARIATE);
		flag.setLifecycle(FlagLifecycle.ACTIVE);
		flag.setOwner("owner");
		flag.setDependsOn(new ArrayList<>());
		flag.setVariations(new ArrayList<>(List.of(new Variation("a", "A", "a", 34, null, null),
				new Variation("b", "B", "b", 33, null, null), new Variation("c", "C", "c", 33, null, null))));

		Map<EnvironmentKey, EnvironmentConfig> environments = new EnumMap<>(EnvironmentKey.class);

		for (EnvironmentKey environment : EnvironmentKey.values()) {
			environments.put(environment, new EnvironmentConfig(true, "a", new ArrayList<>()));
		}

		flag.setEnvironments(environments);

		return flag;
	}
}
