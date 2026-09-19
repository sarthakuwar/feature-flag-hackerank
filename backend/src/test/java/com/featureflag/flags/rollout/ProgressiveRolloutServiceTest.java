package com.featureflag.flags.rollout;

import static com.featureflag.flags.rollout.RolloutFixtures.RULE_ID;
import static com.featureflag.flags.rollout.RolloutFixtures.T0;
import static com.featureflag.flags.rollout.RolloutFixtures.ended;
import static com.featureflag.flags.rollout.RolloutFixtures.flag;
import static com.featureflag.flags.rollout.RolloutFixtures.flagWithRule;
import static com.featureflag.flags.rollout.RolloutFixtures.production;
import static com.featureflag.flags.rollout.RolloutFixtures.rollout;
import static com.featureflag.flags.rollout.RolloutFixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagLifecycle;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagStreamService;
import com.featureflag.flags.ProgressiveRollout;
import com.featureflag.flags.RolloutStatus;
import com.featureflag.flags.Strategy;
import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.rollout.dto.RolloutStageRequest;
import com.featureflag.flags.rollout.dto.StartProgressiveRolloutRequest;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.MutableClock;
import com.featureflag.shared.ValidationException;

@ExtendWith(MockitoExtension.class)
class ProgressiveRolloutServiceTest {

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

	private StartProgressiveRolloutRequest linear(String to) {
		return new StartProgressiveRolloutRequest(RULE_ID, to, null, null, 4, 60, "tester");
	}

	// ---- start: the happy path ----

	@Test
	void startCreatesAnActiveRolloutFromTheRulesCurrentVariation() {
		Flag flag = stored(flagWithRule("f"));

		ProgressiveRollout rollout = service.start("f", PROD, linear("on"));

		assertThat(rollout.getId()).isNotBlank();
		assertThat(rollout.getStatus()).isEqualTo(RolloutStatus.ACTIVE);
		assertThat(rollout.getStrategyId()).isEqualTo(RULE_ID);
		assertThat(rollout.getFromVariationKey()).isEqualTo("off");
		assertThat(rollout.getToVariationKey()).isEqualTo("on");
		assertThat(rollout.getStartedBy()).isEqualTo("tester");
		assertThat(rollout.getStages()).hasSize(4);
		assertThat(production(flag).getProgressiveRollouts()).containsExactly(rollout);
	}

	@Test
	void startDefaultsTheStartTimeToNow() {
		stored(flagWithRule("f"));

		ProgressiveRollout rollout = service.start("f", PROD, linear("on"));

		assertThat(rollout.getStartAt()).isEqualTo(T0);
		assertThat(rollout.getCreatedAt()).isEqualTo(T0);
	}

	@Test
	void startDoesNotChangeWhatTheRuleServesUntilTheRolloutEnds() {
		Flag flag = stored(flagWithRule("f"));

		service.start("f", PROD, linear("on"));

		assertThat(rule(flag, RULE_ID).getServedVariationKey()).isEqualTo("off");
	}

	@Test
	void startAcceptsExplicitStagesAndAFutureStartTime() {
		stored(flagWithRule("f"));
		Instant later = T0.plus(Duration.ofDays(1));
		StartProgressiveRolloutRequest request = new StartProgressiveRolloutRequest(RULE_ID, "on", later,
				List.of(new RolloutStageRequest(10, 0), new RolloutStageRequest(100, 30)), null, null, "tester");

		ProgressiveRollout rollout = service.start("f", PROD, request);

		assertThat(rollout.getStartAt()).isEqualTo(later);
		assertThat(rollout.getStages()).hasSize(2);
		assertThat(rollout.percentageAt(T0)).isZero();
	}

	@Test
	void startPersistsRefreshesTheCacheAuditsAndBroadcasts() {
		Flag flag = stored(flagWithRule("f"));

		service.start("f", PROD, linear("on"));

		verify(flagRepository).save(flag);
		verify(flagCacheService).put(flag);
		verify(auditService).record(eq("f"), eq(AuditAction.ROLLOUT_START), eq("tester"), any());
		verify(flagStreamService).publishFlagChanged(PROD, "f", "ROLLOUT_START");
	}

	@Test
	void startWorksOnEveryTargetableRuleType() {
		Flag flag = stored(flag("f"));
		production(flag).getStrategies().add(RolloutFixtures.userIdRule("user-rule", 0, List.of("a"), "off"));

		ProgressiveRollout rollout = service.start("f", PROD,
				new StartProgressiveRolloutRequest("user-rule", "on", null, null, 3, 30, "tester"));

		assertThat(rollout.getStrategyId()).isEqualTo("user-rule");

		Flag other = stored(flag("g"));
		production(other).getStrategies().add(RolloutFixtures.percentageRule("pct-rule", 0, 50, "off"));

		ProgressiveRollout second = service.start("g", PROD,
				new StartProgressiveRolloutRequest("pct-rule", "on", null, null, 3, 30, "tester"));

		assertThat(second.getStrategyId()).isEqualTo("pct-rule");
	}

	@Test
	void anEarlierCompletedOrStoppedRolloutDoesNotBlockANewOne() {
		Flag flag = stored(flagWithRule("f"));
		production(flag).getProgressiveRollouts().add(ended(rollout("old-1", RULE_ID, "off", "on", T0), RolloutStatus.COMPLETED, "on", 100));
		production(flag).getProgressiveRollouts().add(ended(rollout("old-2", RULE_ID, "off", "on", T0), RolloutStatus.STOPPED, "off", 25));

		ProgressiveRollout rollout = service.start("f", PROD, linear("beta"));

		assertThat(rollout.getStatus()).isEqualTo(RolloutStatus.ACTIVE);
		assertThat(production(flag).getProgressiveRollouts()).hasSize(3);
	}

	@Test
	void anActiveRolloutInAnotherEnvironmentDoesNotBlockThisOne() {
		Flag flag = stored(flagWithRule("f"));
		flag.getEnvironments().get(EnvironmentKey.STAGING).getProgressiveRollouts().add(rollout("r-staging", RULE_ID, "off", "on", T0));

		ProgressiveRollout rollout = service.start("f", PROD, linear("on"));

		assertThat(rollout.getStatus()).isEqualTo(RolloutStatus.ACTIVE);
	}

	// ---- start: eligibility guards ----

	@Test
	void startIsRefusedWhileAnotherRolloutIsActiveInTheEnvironment() {
		Flag flag = stored(flagWithRule("f"));
		production(flag).getStrategies().add(RolloutFixtures.userIdRule("second-rule", 1, List.of("a"), "off"));
		production(flag).getProgressiveRollouts().add(rollout("active", RULE_ID, "off", "on", T0));

		assertThatThrownBy(() -> service.start("f", PROD, new StartProgressiveRolloutRequest("second-rule", "on", null, null, 3, 30, "tester")))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.getStatusCode()).isEqualTo(409);
					assertThat(exception.getCode()).isEqualTo("ROLLOUT_ALREADY_ACTIVE");
				});
		verify(flagRepository, never()).save(any());
	}

	@Test
	void startIsRefusedWhenTheEnvironmentHasAnExperiment() {
		Flag flag = stored(flagWithRule("f"));
		production(flag).getStrategies().add(RolloutFixtures.experimentRule("exp", 1));

		assertThatThrownBy(() -> service.start("f", PROD, linear("on"))).isInstanceOfSatisfying(ApiException.class, exception -> {
			assertThat(exception.getStatusCode()).isEqualTo(409);
			assertThat(exception.getCode()).isEqualTo("EXPERIMENT_ACTIVE");
		});
		verify(flagRepository, never()).save(any());
	}

	@Test
	void anExperimentInAnotherEnvironmentDoesNotBlockThisOne() {
		Flag flag = stored(flagWithRule("f"));
		flag.getEnvironments().get(EnvironmentKey.STAGING).getStrategies().add(RolloutFixtures.experimentRule("exp", 1));

		assertThat(service.start("f", PROD, linear("on")).getStatus()).isEqualTo(RolloutStatus.ACTIVE);
	}

	@Test
	void startIsRefusedForAnArchivedFlag() {
		Flag flag = stored(flagWithRule("f"));
		flag.setLifecycle(FlagLifecycle.ARCHIVED);

		assertThatThrownBy(() -> service.start("f", PROD, linear("on"))).isInstanceOfSatisfying(ApiException.class, exception -> {
			assertThat(exception.getStatusCode()).isEqualTo(409);
			assertThat(exception.getCode()).isEqualTo("FLAG_ARCHIVED");
		});
	}

	@Test
	void startFailsWithNotFoundForAnUnknownFlag() {
		when(flagRepository.findByKey("missing")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.start("missing", PROD, linear("on"))).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.getCode()).isEqualTo("FLAG_NOT_FOUND"));
	}

	@Test
	void startFailsWithNotFoundWhenTheEnvironmentHasNoConfiguration() {
		Flag flag = stored(flagWithRule("f"));
		flag.getEnvironments().remove(PROD);

		assertThatThrownBy(() -> service.start("f", PROD, linear("on"))).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.getCode()).isEqualTo("ENVIRONMENT_NOT_FOUND"));
	}

	@Test
	void startFailsWithNotFoundForAnUnknownRule() {
		stored(flagWithRule("f"));

		assertThatThrownBy(() -> service.start("f", PROD, new StartProgressiveRolloutRequest("nope", "on", null, null, 4, 60, "tester")))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.getStatusCode()).isEqualTo(404);
					assertThat(exception.getCode()).isEqualTo("STRATEGY_NOT_FOUND");
				});
	}

	// ---- start: request validation ----

	@Test
	void startRejectsAVariationThatDoesNotExistOnTheFlag() {
		stored(flagWithRule("f"));

		assertThatThrownBy(() -> service.start("f", PROD, linear("ghost"))).isInstanceOf(ValidationException.class);
		verify(flagRepository, never()).save(any());
	}

	@Test
	void startRejectsRollingOutTheVariationTheRuleAlreadyServes() {
		stored(flagWithRule("f"));

		assertThatThrownBy(() -> service.start("f", PROD, linear("off"))).isInstanceOf(ValidationException.class);
		verify(flagRepository, never()).save(any());
	}

	@Test
	void startRejectsMissingRequiredFields() {
		stored(flagWithRule("f"));

		assertThatThrownBy(() -> service.start("f", PROD, new StartProgressiveRolloutRequest(null, null, null, null, 4, 60, " ")))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void startRejectsAnInvalidSchedule() {
		stored(flagWithRule("f"));
		StartProgressiveRolloutRequest request = new StartProgressiveRolloutRequest(RULE_ID, "on", null,
				List.of(new RolloutStageRequest(50, 0), new RolloutStageRequest(40, 10)), null, null, "tester");

		assertThatThrownBy(() -> service.start("f", PROD, request)).isInstanceOf(ValidationException.class);
	}

	@Test
	void startRejectsAStartTimeInThePast() {
		stored(flagWithRule("f"));
		StartProgressiveRolloutRequest request = new StartProgressiveRolloutRequest(RULE_ID, "on", T0.minus(Duration.ofHours(1)), null, 4, 60,
				"tester");

		assertThatThrownBy(() -> service.start("f", PROD, request)).isInstanceOf(ValidationException.class);
	}

	@Test
	void startToleratesASmallClockSkewOnTheStartTime() {
		stored(flagWithRule("f"));
		StartProgressiveRolloutRequest request = new StartProgressiveRolloutRequest(RULE_ID, "on", T0.minusSeconds(30), null, 4, 60,
				"tester");

		assertThat(service.start("f", PROD, request).getStartAt()).isEqualTo(T0.minusSeconds(30));
	}

	// ---- stop ----

	private ProgressiveRollout activeRolloutOn(Flag flag) {
		ProgressiveRollout rollout = rollout("r1", RULE_ID, "off", "on", T0);
		production(flag).getProgressiveRollouts().add(rollout);

		return rollout;
	}

	@Test
	void stopPausingOnTheOldVariationKeepsTheRuleOnIt() {
		Flag flag = stored(flagWithRule("f"));
		ProgressiveRollout rollout = activeRolloutOn(flag);
		clock.set(T0.plus(Duration.ofMinutes(70)));

		ProgressiveRollout stopped = service.stop("f", PROD, "r1", "off", "operator");

		assertThat(stopped).isSameAs(rollout);
		assertThat(stopped.getStatus()).isEqualTo(RolloutStatus.STOPPED);
		assertThat(stopped.getResolvedVariationKey()).isEqualTo("off");
		assertThat(stopped.getFinalPercentage()).isEqualTo(50);
		assertThat(stopped.getEndedBy()).isEqualTo("operator");
		assertThat(stopped.getEndedAt()).isEqualTo(T0.plus(Duration.ofMinutes(70)));
		assertThat(rule(flag, RULE_ID).getServedVariationKey()).isEqualTo("off");
	}

	@Test
	void stopFinishingEarlyMakesTheRuleServeTheNewVariation() {
		Flag flag = stored(flagWithRule("f"));
		activeRolloutOn(flag);

		service.stop("f", PROD, "r1", "on", "operator");

		assertThat(rule(flag, RULE_ID).getServedVariationKey()).isEqualTo("on");
	}

	@Test
	void stopCanChooseAnyVariationOnTheFlag() {
		Flag flag = stored(flagWithRule("f"));
		activeRolloutOn(flag);

		ProgressiveRollout stopped = service.stop("f", PROD, "r1", "beta", "operator");

		assertThat(stopped.getResolvedVariationKey()).isEqualTo("beta");
		assertThat(rule(flag, RULE_ID).getServedVariationKey()).isEqualTo("beta");
	}

	@Test
	void stopBeforeTheRolloutHasStartedRecordsZeroPercent() {
		Flag flag = stored(flagWithRule("f"));
		production(flag).getProgressiveRollouts().add(rollout("r1", RULE_ID, "off", "on", T0.plus(Duration.ofDays(1))));

		ProgressiveRollout stopped = service.stop("f", PROD, "r1", "off", "operator");

		assertThat(stopped.getFinalPercentage()).isZero();
	}

	@Test
	void stopPersistsRefreshesTheCacheAuditsAndBroadcasts() {
		Flag flag = stored(flagWithRule("f"));
		activeRolloutOn(flag);

		service.stop("f", PROD, "r1", "off", "operator");

		verify(flagRepository).save(flag);
		verify(flagCacheService).put(flag);
		verify(auditService).record(eq("f"), eq(AuditAction.ROLLOUT_STOP), eq("operator"), any());
		verify(flagStreamService).publishFlagChanged(PROD, "f", "ROLLOUT_STOP");
	}

	@Test
	void stopLeavesOtherRulesAlone() {
		Flag flag = stored(flagWithRule("f"));
		production(flag).getStrategies().add(RolloutFixtures.userIdRule("other", 1, List.of("a"), "beta"));
		activeRolloutOn(flag);

		service.stop("f", PROD, "r1", "on", "operator");

		assertThat(rule(flag, "other").getServedVariationKey()).isEqualTo("beta");
	}

	@Test
	void stopFailsWithNotFoundForAnUnknownRollout() {
		stored(flagWithRule("f"));

		assertThatThrownBy(() -> service.stop("f", PROD, "nope", "off", "operator")).isInstanceOfSatisfying(ApiException.class,
				exception -> {
					assertThat(exception.getStatusCode()).isEqualTo(404);
					assertThat(exception.getCode()).isEqualTo("ROLLOUT_NOT_FOUND");
				});
	}

	@Test
	void stopIsRefusedForARolloutThatAlreadyEnded() {
		Flag flag = stored(flagWithRule("f"));
		production(flag).getProgressiveRollouts().add(ended(rollout("r1", RULE_ID, "off", "on", T0), RolloutStatus.COMPLETED, "on", 100));

		assertThatThrownBy(() -> service.stop("f", PROD, "r1", "off", "operator")).isInstanceOfSatisfying(ApiException.class,
				exception -> {
					assertThat(exception.getStatusCode()).isEqualTo(409);
					assertThat(exception.getCode()).isEqualTo("ROLLOUT_NOT_ACTIVE");
				});
		verify(flagRepository, never()).save(any());
	}

	@Test
	void stopRejectsAVariationNotOnTheFlagAndABlankActor() {
		Flag flag = stored(flagWithRule("f"));
		activeRolloutOn(flag);

		assertThatThrownBy(() -> service.stop("f", PROD, "r1", "ghost", "operator")).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.stop("f", PROD, "r1", null, "operator")).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> service.stop("f", PROD, "r1", "off", " ")).isInstanceOf(ValidationException.class);
		assertThat(production(flag).activeProgressiveRollout()).isPresent();
		verify(flagRepository, never()).save(any());
	}

	@Test
	void stopIsRefusedForAnArchivedFlag() {
		Flag flag = stored(flagWithRule("f"));
		activeRolloutOn(flag);
		flag.setLifecycle(FlagLifecycle.ARCHIVED);

		assertThatThrownBy(() -> service.stop("f", PROD, "r1", "off", "operator")).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.getCode()).isEqualTo("FLAG_ARCHIVED"));
	}

	@Test
	void aNewRolloutCanStartAfterTheActiveOneIsStopped() {
		Flag flag = stored(flagWithRule("f"));
		activeRolloutOn(flag);
		service.stop("f", PROD, "r1", "off", "operator");

		ProgressiveRollout next = service.start("f", PROD, linear("beta"));

		assertThat(next.getStatus()).isEqualTo(RolloutStatus.ACTIVE);
		assertThat(next.getFromVariationKey()).isEqualTo("off");
	}

	@Test
	void aSecondStopOfTheSameRolloutIsRefused() {
		Flag flag = stored(flagWithRule("f"));
		activeRolloutOn(flag);
		service.stop("f", PROD, "r1", "off", "operator");

		assertThatThrownBy(() -> service.stop("f", PROD, "r1", "on", "operator")).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.getCode()).isEqualTo("ROLLOUT_NOT_ACTIVE"));
		assertThat(rule(flag, RULE_ID).getServedVariationKey()).isEqualTo("off");
	}

	// ---- completion ----

	private Flag flagInCache(Flag flag) {
		when(flagCacheService.all()).thenReturn(List.of(flag));
		lenient().when(flagRepository.findByKey(flag.getKey())).thenReturn(Optional.of(flag));

		return flag;
	}

	@Test
	void completeDueRolloutsPromotesTheRuleAndRecordsTheCompletion() {
		Flag flag = flagInCache(flagWithRule("f"));
		ProgressiveRollout rollout = activeRolloutOn(flag);
		clock.set(T0.plus(Duration.ofMinutes(120)));

		int completed = service.completeDueRollouts();

		assertThat(completed).isEqualTo(1);
		assertThat(rollout.getStatus()).isEqualTo(RolloutStatus.COMPLETED);
		assertThat(rollout.getResolvedVariationKey()).isEqualTo("on");
		assertThat(rollout.getFinalPercentage()).isEqualTo(100);
		assertThat(rollout.getEndedBy()).isEqualTo("system:rollout");
		assertThat(rule(flag, RULE_ID).getServedVariationKey()).isEqualTo("on");
		verify(flagRepository).save(flag);
		verify(flagCacheService).put(flag);
		verify(auditService).record(eq("f"), eq(AuditAction.ROLLOUT_COMPLETE), eq("system:rollout"), any());
		verify(flagStreamService).publishFlagChanged(PROD, "f", "ROLLOUT_COMPLETE");
	}

	@Test
	void completeDueRolloutsLeavesARolloutAloneUntilItsFinalStageBegins() {
		Flag flag = flagInCache(flagWithRule("f"));
		ProgressiveRollout rollout = activeRolloutOn(flag);
		clock.set(T0.plus(Duration.ofMinutes(119)).plusSeconds(59));

		assertThat(service.completeDueRollouts()).isZero();
		assertThat(rollout.getStatus()).isEqualTo(RolloutStatus.ACTIVE);
		verify(flagRepository, never()).save(any());
	}

	@Test
	void completeDueRolloutsIgnoresARolloutThatHasNotStartedYet() {
		Flag flag = flagInCache(flagWithRule("f"));
		production(flag).getProgressiveRollouts().add(rollout("r1", RULE_ID, "off", "on", T0.plus(Duration.ofDays(1))));

		assertThat(service.completeDueRollouts()).isZero();
	}

	@Test
	void completeDueRolloutsSkipsArchivedFlags() {
		Flag flag = flagInCache(flagWithRule("f"));
		activeRolloutOn(flag);
		flag.setLifecycle(FlagLifecycle.ARCHIVED);
		clock.set(T0.plus(Duration.ofDays(1)));

		assertThat(service.completeDueRollouts()).isZero();
		verify(flagRepository, never()).save(any());
	}

	@Test
	void completeDueRolloutsRechecksTheStoredFlagSoARolloutStoppedMeanwhileIsLeftAlone() {
		Flag stale = flagWithRule("f");
		activeRolloutOn(stale);
		when(flagCacheService.all()).thenReturn(List.of(stale));

		Flag fresh = flagWithRule("f");
		production(fresh).getProgressiveRollouts().add(ended(rollout("r1", RULE_ID, "off", "on", T0), RolloutStatus.STOPPED, "off", 25));
		when(flagRepository.findByKey("f")).thenReturn(Optional.of(fresh));
		clock.set(T0.plus(Duration.ofDays(1)));

		assertThat(service.completeDueRollouts()).isZero();
		verify(flagRepository, never()).save(any());
		assertThat(rule(fresh, RULE_ID).getServedVariationKey()).isEqualTo("off");
	}

	@Test
	void completeDueRolloutsHandlesEveryDueRolloutAcrossFlagsAndEnvironments() {
		Flag first = flagWithRule("f1");
		Flag second = flagWithRule("f2");
		activeRolloutOn(first);
		first.getEnvironments().get(EnvironmentKey.STAGING).getProgressiveRollouts().add(rollout("r-stg", RULE_ID, "off", "beta", T0));
		activeRolloutOn(second);
		when(flagCacheService.all()).thenReturn(List.of(first, second));
		when(flagRepository.findByKey("f1")).thenReturn(Optional.of(first));
		when(flagRepository.findByKey("f2")).thenReturn(Optional.of(second));
		clock.set(T0.plus(Duration.ofDays(1)));

		assertThat(service.completeDueRollouts()).isEqualTo(3);
		assertThat(first.getEnvironments().get(EnvironmentKey.STAGING).getStrategies().get(0).getServedVariationKey()).isEqualTo("beta");
		assertThat(rule(second, RULE_ID).getServedVariationKey()).isEqualTo("on");
	}

	@Test
	void oneFlagFailingToCompleteDoesNotStopTheOthers() {
		Flag broken = flagWithRule("broken");
		Flag healthy = flagWithRule("healthy");
		activeRolloutOn(broken);
		activeRolloutOn(healthy);
		when(flagCacheService.all()).thenReturn(List.of(broken, healthy));
		when(flagRepository.findByKey("broken")).thenReturn(Optional.of(broken));
		when(flagRepository.findByKey("healthy")).thenReturn(Optional.of(healthy));
		when(flagRepository.save(broken)).thenThrow(new IllegalStateException("db down"));
		clock.set(T0.plus(Duration.ofDays(1)));

		assertThat(service.completeDueRollouts()).isEqualTo(1);
		assertThat(rule(healthy, RULE_ID).getServedVariationKey()).isEqualTo("on");
	}

	@Test
	void completeDueRolloutsDoesNothingWhenNoFlagHasARollout() {
		when(flagCacheService.all()).thenReturn(List.of(flagWithRule("f")));

		assertThat(service.completeDueRollouts()).isZero();
		verify(flagRepository, never()).findByKey(any());
	}

	// ---- list / get ----

	@Test
	void listReturnsRolloutsNewestFirst() {
		Flag flag = stored(flagWithRule("f"));
		production(flag).getProgressiveRollouts().add(rollout("oldest", RULE_ID, "off", "on", T0));
		production(flag).getProgressiveRollouts().add(rollout("newest", RULE_ID, "off", "on", T0.plus(Duration.ofDays(2))));
		production(flag).getProgressiveRollouts().add(rollout("middle", RULE_ID, "off", "on", T0.plus(Duration.ofDays(1))));

		List<ProgressiveRollout> rollouts = service.list("f", PROD, null);

		assertThat(rollouts).extracting(ProgressiveRollout::getId).containsExactly("newest", "middle", "oldest");
	}

	@Test
	void listCanBeNarrowedToAStatus() {
		Flag flag = stored(flagWithRule("f"));
		production(flag).getProgressiveRollouts().add(ended(rollout("done", RULE_ID, "off", "on", T0), RolloutStatus.COMPLETED, "on", 100));
		production(flag).getProgressiveRollouts().add(rollout("live", RULE_ID, "off", "on", T0.plus(Duration.ofDays(1))));

		assertThat(service.list("f", PROD, RolloutStatus.ACTIVE)).extracting(ProgressiveRollout::getId).containsExactly("live");
		assertThat(service.list("f", PROD, RolloutStatus.COMPLETED)).extracting(ProgressiveRollout::getId).containsExactly("done");
		assertThat(service.list("f", PROD, RolloutStatus.STOPPED)).isEmpty();
	}

	@Test
	void listIsEmptyForAFlagThatNeverRanARollout() {
		stored(flagWithRule("f"));

		assertThat(service.list("f", PROD, null)).isEmpty();
	}

	@Test
	void listAndGetStillWorkForAnArchivedFlag() {
		Flag flag = stored(flagWithRule("f"));
		activeRolloutOn(flag);
		flag.setLifecycle(FlagLifecycle.ARCHIVED);

		assertThat(service.list("f", PROD, null)).hasSize(1);
		assertThat(service.get("f", PROD, "r1").getId()).isEqualTo("r1");
	}

	@Test
	void listFailsForAnUnknownFlagOrEnvironment() {
		when(flagRepository.findByKey("missing")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.list("missing", PROD, null)).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.getCode()).isEqualTo("FLAG_NOT_FOUND"));

		Flag flag = stored(flagWithRule("f"));
		flag.getEnvironments().remove(PROD);

		assertThatThrownBy(() -> service.list("f", PROD, null)).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.getCode()).isEqualTo("ENVIRONMENT_NOT_FOUND"));
	}

	@Test
	void getFailsForAnUnknownRollout() {
		stored(flagWithRule("f"));

		assertThatThrownBy(() -> service.get("f", PROD, "nope")).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.getCode()).isEqualTo("ROLLOUT_NOT_FOUND"));
	}

	@Test
	void theStoredRuleInstanceIsTheOneRewrittenOnStop() {
		Flag flag = stored(flagWithRule("f"));
		activeRolloutOn(flag);
		Strategy before = rule(flag, RULE_ID);

		service.stop("f", PROD, "r1", "on", "operator");

		assertThat(rule(flag, RULE_ID)).isSameAs(before);
		assertThat(before.getServedVariationKey()).isEqualTo("on");
	}
}
