package com.featureflag.flags.rollout;

import static com.featureflag.flags.rollout.RolloutFixtures.RULE_ID;
import static com.featureflag.flags.rollout.RolloutFixtures.T0;
import static com.featureflag.flags.rollout.RolloutFixtures.TARGETING_RULE_ID;
import static com.featureflag.flags.rollout.RolloutFixtures.experimentRule;
import static com.featureflag.flags.rollout.RolloutFixtures.flagWithTargetingRule;
import static com.featureflag.flags.rollout.RolloutFixtures.production;
import static com.featureflag.flags.rollout.RolloutFixtures.ruleRollout;
import static com.featureflag.flags.rollout.RolloutFixtures.targetingRuleOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagStreamService;
import com.featureflag.flags.ProgressiveRollout;
import com.featureflag.flags.RolloutStatus;
import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.rollout.dto.StartProgressiveRolloutRequest;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.MutableClock;
import com.featureflag.shared.ValidationException;

/** Starting, stopping and completing a progressive rollout whose target is a targeting rule. */
@ExtendWith(MockitoExtension.class)
class RuleProgressiveRolloutServiceTest {

	private static final EnvironmentKey PROD = EnvironmentKey.PRODUCTION;

	@Mock
	private FlagRepository flagRepository;

	@Mock
	private FlagCacheService flagCacheService;

	@Mock
	private FlagStreamService flagStreamService;

	@Mock
	private AuditService auditService;

	private MutableClock clock;

	private ProgressiveRolloutService service;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(T0);
		service = new ProgressiveRolloutService(flagRepository, flagCacheService, flagStreamService, auditService, clock);
		lenient().when(flagRepository.save(any(Flag.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private Flag stored(Flag flag) {
		when(flagRepository.findByKey(flag.getKey())).thenReturn(Optional.of(flag));

		return flag;
	}

	private static StartProgressiveRolloutRequest ramp(String ruleId, String to) {
		return StartProgressiveRolloutRequest.forRule(ruleId, to, null, null, 4, 60, "tester");
	}

	private ProgressiveRollout activeRuleRollout(Flag flag) {
		ProgressiveRollout rollout = ruleRollout("r1", TARGETING_RULE_ID, "off", "on", T0);
		production(flag).getProgressiveRollouts().add(rollout);

		return rollout;
	}

	// ---- start ----

	@Test
	void startingOnARuleRecordsTheRuleAndTakesFromFromWhatTheRuleServes() {
		Flag flag = stored(flagWithTargetingRule("f"));

		ProgressiveRollout rollout = service.start("f", PROD, ramp(TARGETING_RULE_ID, "on"));

		assertThat(rollout.getRuleId()).isEqualTo(TARGETING_RULE_ID);
		assertThat(rollout.getStrategyId()).isNull();
		assertThat(rollout.targetsRule()).isTrue();
		assertThat(rollout.getFromVariationKey()).isEqualTo("off");
		assertThat(rollout.getToVariationKey()).isEqualTo("on");
		assertThat(rollout.getStatus()).isEqualTo(RolloutStatus.ACTIVE);
		assertThat(production(flag).getProgressiveRollouts()).containsExactly(rollout);
		verify(flagRepository).save(flag);
		verify(flagCacheService).put(flag);
		verify(auditService).record(eq("f"), eq(AuditAction.ROLLOUT_START), eq("tester"), any());
		verify(flagStreamService).publishFlagChanged(PROD, "f", "ROLLOUT_START");
	}

	@Test
	void startingDoesNotChangeWhatTheRuleServesUntilTheRolloutEnds() {
		Flag flag = stored(flagWithTargetingRule("f"));

		service.start("f", PROD, ramp(TARGETING_RULE_ID, "on"));

		assertThat(targetingRuleOf(flag, TARGETING_RULE_ID).getServedVariationKey()).isEqualTo("off");
	}

	@Test
	void theOriginalStrategyFormStillWorks() {
		Flag flag = stored(flagWithTargetingRule("f"));
		production(flag).getStrategies().add(RolloutFixtures.attributeRule(RULE_ID, 0, "plan", "pro", "off"));

		ProgressiveRollout rollout = service.start("f", PROD, new StartProgressiveRolloutRequest(RULE_ID, "on", null, null, 4, 60, "tester"));

		assertThat(rollout.getStrategyId()).isEqualTo(RULE_ID);
		assertThat(rollout.getRuleId()).isNull();
		assertThat(rollout.targetsRule()).isFalse();
	}

	@Test
	void exactlyOneOfRuleIdAndStrategyIdIsRequired() {
		stored(flagWithTargetingRule("f"));

		assertThatThrownBy(() -> service.start("f", PROD, new StartProgressiveRolloutRequest(RULE_ID, "on", null, null, 4, 60, "tester", TARGETING_RULE_ID)))
				.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.start("f", PROD, new StartProgressiveRolloutRequest(null, "on", null, null, 4, 60, "tester", " ")))
				.isInstanceOf(ValidationException.class);
		verify(flagRepository, never()).save(any());
	}

	@Test
	void anUnknownRuleIsNotFoundAndNamesTheRule() {
		stored(flagWithTargetingRule("f"));

		assertThatThrownBy(() -> service.start("f", PROD, ramp("ghost", "on"))).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("statusCode", 404).hasFieldOrPropertyWithValue("code", "RULE_NOT_FOUND");
	}

	@Test
	void anUnknownStrategyIsStillReportedAsAStrategy() {
		stored(flagWithTargetingRule("f"));

		assertThatThrownBy(() -> service.start("f", PROD, new StartProgressiveRolloutRequest("ghost", "on", null, null, 4, 60, "tester")))
				.isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "STRATEGY_NOT_FOUND");
	}

	@Test
	void aRuleThatServesAPercentageRolloutCannotBeRamped() {
		Flag flag = stored(flagWithTargetingRule("f"));
		targetingRuleOf(flag, TARGETING_RULE_ID).setServedVariationKey(null);
		targetingRuleOf(flag, TARGETING_RULE_ID).setRolloutWeights(new LinkedHashMap<>(Map.of("off", 50, "on", 50)));

		assertThatThrownBy(() -> service.start("f", PROD, ramp(TARGETING_RULE_ID, "beta"))).isInstanceOf(ValidationException.class);
		verify(flagRepository, never()).save(any());
	}

	@Test
	void aDisabledRuleCannotBeRamped() {
		Flag flag = stored(flagWithTargetingRule("f"));
		targetingRuleOf(flag, TARGETING_RULE_ID).setEnabled(false);

		assertThatThrownBy(() -> service.start("f", PROD, ramp(TARGETING_RULE_ID, "on"))).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("statusCode", 409).hasFieldOrPropertyWithValue("code", "RULE_DISABLED");
	}

	@Test
	void rampingToWhatTheRuleAlreadyServesIsRejected() {
		stored(flagWithTargetingRule("f"));

		assertThatThrownBy(() -> service.start("f", PROD, ramp(TARGETING_RULE_ID, "off"))).isInstanceOf(ValidationException.class);
	}

	@Test
	void onlyOneRolloutMayBeActiveInAnEnvironmentAcrossRulesAndStrategies() {
		Flag flag = stored(flagWithTargetingRule("f"));
		production(flag).getRules().add(RolloutFixtures.targetingRule("second", "country", "US", "off"));
		activeRuleRollout(flag);

		assertThatThrownBy(() -> service.start("f", PROD, ramp("second", "on"))).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("code", "ROLLOUT_ALREADY_ACTIVE");
	}

	@Test
	void aRuleCannotBeRampedWhileTheEnvironmentRunsAnExperiment() {
		Flag flag = stored(flagWithTargetingRule("f"));
		production(flag).getStrategies().add(experimentRule("exp", 0));

		assertThatThrownBy(() -> service.start("f", PROD, ramp(TARGETING_RULE_ID, "on"))).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("code", "EXPERIMENT_ACTIVE");
	}

	@Test
	void aRuleCanBeRampedAgainAfterItsFirstRolloutEnds() {
		Flag flag = stored(flagWithTargetingRule("f"));
		ProgressiveRollout first = service.start("f", PROD, ramp(TARGETING_RULE_ID, "on"));
		service.stop("f", PROD, first.getId(), "on", "operator");

		ProgressiveRollout second = service.start("f", PROD, ramp(TARGETING_RULE_ID, "beta"));

		assertThat(second.getFromVariationKey()).as("the rule now serves what the first rollout ended on").isEqualTo("on");
		assertThat(production(flag).getProgressiveRollouts()).hasSize(2);
	}

	// ---- stop ----

	@Test
	void stoppingRewritesTheRuleToTheChosenVariation() {
		Flag flag = stored(flagWithTargetingRule("f"));
		ProgressiveRollout rollout = activeRuleRollout(flag);
		clock.set(T0.plus(Duration.ofMinutes(70)));

		ProgressiveRollout stopped = service.stop("f", PROD, "r1", "beta", "operator");

		assertThat(stopped).isSameAs(rollout);
		assertThat(stopped.getStatus()).isEqualTo(RolloutStatus.STOPPED);
		assertThat(stopped.getResolvedVariationKey()).isEqualTo("beta");
		assertThat(stopped.getFinalPercentage()).isEqualTo(50);
		assertThat(targetingRuleOf(flag, TARGETING_RULE_ID).getServedVariationKey()).isEqualTo("beta");
		verify(auditService).record(eq("f"), eq(AuditAction.ROLLOUT_STOP), eq("operator"), any());
	}

	@Test
	void stoppingLeavesTheRulesAudienceAndPositionAlone() {
		Flag flag = stored(flagWithTargetingRule("f"));
		activeRuleRollout(flag);
		List<com.featureflag.flags.targeting.Clause> clausesBefore = List.copyOf(targetingRuleOf(flag, TARGETING_RULE_ID).getClauses());

		service.stop("f", PROD, "r1", "on", "operator");

		assertThat(targetingRuleOf(flag, TARGETING_RULE_ID).getClauses()).containsExactlyElementsOf(clausesBefore);
		assertThat(production(flag).getRules()).hasSize(1);
	}

	// ---- completion ----

	@Test
	void completionMakesTheRuleServeTheNewVariationPermanently() {
		Flag flag = stored(flagWithTargetingRule("f"));
		when(flagCacheService.all()).thenReturn(List.of(flag));
		ProgressiveRollout rollout = activeRuleRollout(flag);
		clock.set(T0.plus(Duration.ofMinutes(120)));

		int completed = service.completeDueRollouts();

		assertThat(completed).isEqualTo(1);
		assertThat(rollout.getStatus()).isEqualTo(RolloutStatus.COMPLETED);
		assertThat(rollout.getResolvedVariationKey()).isEqualTo("on");
		assertThat(rollout.getFinalPercentage()).isEqualTo(100);
		assertThat(targetingRuleOf(flag, TARGETING_RULE_ID).getServedVariationKey()).isEqualTo("on");
		verify(auditService).record(eq("f"), eq(AuditAction.ROLLOUT_COMPLETE), eq("system:rollout"), any());
		verify(flagStreamService).publishFlagChanged(PROD, "f", "ROLLOUT_COMPLETE");
	}

	@Test
	void completionWaitsForTheFinalStage() {
		Flag flag = flagWithTargetingRule("f");
		lenient().when(flagRepository.findByKey("f")).thenReturn(Optional.of(flag));
		when(flagCacheService.all()).thenReturn(List.of(flag));
		ProgressiveRollout rollout = activeRuleRollout(flag);
		clock.set(T0.plus(Duration.ofMinutes(119)));

		assertThat(service.completeDueRollouts()).isZero();
		assertThat(rollout.getStatus()).isEqualTo(RolloutStatus.ACTIVE);
		assertThat(targetingRuleOf(flag, TARGETING_RULE_ID).getServedVariationKey()).isEqualTo("off");
	}

	@Test
	void aRolloutWhoseRuleWasRemovedBehindItsBackStillEndsWithoutFailing() {
		Flag flag = stored(flagWithTargetingRule("f"));
		when(flagCacheService.all()).thenReturn(List.of(flag));
		ProgressiveRollout rollout = activeRuleRollout(flag);
		production(flag).getRules().clear();
		clock.set(T0.plus(Duration.ofMinutes(120)));

		assertThat(service.completeDueRollouts()).isEqualTo(1);
		assertThat(rollout.getStatus()).as("history is closed out even though there was no rule left to rewrite")
				.isEqualTo(RolloutStatus.COMPLETED);
	}
}
