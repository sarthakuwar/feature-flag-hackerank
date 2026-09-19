package com.featureflag.flags;

import static com.featureflag.flags.rollout.RolloutFixtures.RULE_ID;
import static com.featureflag.flags.rollout.RolloutFixtures.T0;
import static com.featureflag.flags.rollout.RolloutFixtures.attributeRule;
import static com.featureflag.flags.rollout.RolloutFixtures.ended;
import static com.featureflag.flags.rollout.RolloutFixtures.flagWithRule;
import static com.featureflag.flags.rollout.RolloutFixtures.percentageRule;
import static com.featureflag.flags.rollout.RolloutFixtures.production;
import static com.featureflag.flags.rollout.RolloutFixtures.rollout;
import static com.featureflag.flags.rollout.RolloutFixtures.stages;
import static com.featureflag.flags.rollout.RolloutFixtures.userIdRule;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.featureflag.flags.rollout.RolloutFixtures;

/**
 * Evaluation of a rule that has an ACTIVE progressive rollout: 25% at start, 50% after an hour,
 * 100% after two (see {@link RolloutFixtures#rollout}). Audience = users matching the rule; within
 * it a user gets the new variation iff their stable-hash bucket is below the current percentage.
 */
class FlagEvaluationProgressiveRolloutTest {

	private static final int USERS = 2000;

	private final FlagEvaluationService atStart = serviceAt(T0);

	private Flag flagWithActiveRollout() {
		Flag flag = flagWithRule("rollout-flag");
		production(flag).getProgressiveRollouts().add(rollout("r1", RULE_ID, "off", "on", T0));

		return flag;
	}

	private static FlagEvaluationService serviceAt(Instant instant) {
		return new FlagEvaluationService(Clock.fixed(instant, ZoneOffset.UTC));
	}

	private static UserContext proUser(int index) {
		return new UserContext("user-" + index, Map.of("plan", "pro"));
	}

	private EvaluationResult evaluate(FlagEvaluationService service, Flag flag, UserContext user) {
		return service.evaluate(flag, EnvironmentKey.PRODUCTION, user);
	}

	@Test
	void usersOutsideTheRulesAudienceAreUnaffectedByItsRollout() {
		Flag flag = flagWithActiveRollout();
		FlagEvaluationService finished = serviceAt(T0.plus(Duration.ofDays(1)));

		EvaluationResult result = evaluate(finished, flag, new UserContext("user-1", Map.of("plan", "free")));

		assertThat(result.variationKey()).isEqualTo("off");
		assertThat(result.reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
	}

	@Test
	void everyoneInTheAudienceKeepsTheOldVariationBeforeTheRolloutStarts() {
		Flag flag = flagWithActiveRollout();
		FlagEvaluationService beforeStart = serviceAt(T0.minusSeconds(1));

		for (int index = 0; index < 300; index++) {
			assertThat(evaluate(beforeStart, flag, proUser(index)).variationKey()).isEqualTo("off");
		}
	}

	@Test
	void resultsInsideARolloutCarryTheRolloutReasonAndTheRuleId() {
		Flag flag = flagWithActiveRollout();

		EvaluationResult result = evaluate(atStart, flag, proUser(1));

		assertThat(result.reason()).isEqualTo(EvaluationReason.PROGRESSIVE_ROLLOUT);
		assertThat(result.matchedStrategyId()).isEqualTo(RULE_ID);
	}

	@Test
	void theServedValueIsTheChosenVariationsValue() {
		Flag flag = flagWithActiveRollout();
		FlagEvaluationService finished = serviceAt(T0.plus(Duration.ofHours(3)));

		EvaluationResult result = evaluate(finished, flag, proUser(1));

		assertThat(result.variationKey()).isEqualTo("on");
		assertThat(result.value()).isEqualTo("true");
	}

	@Test
	void aUserGetsTheNewVariationExactlyWhenTheirBucketIsBelowTheCurrentPercentage() {
		Flag flag = flagWithActiveRollout();
		Map<Duration, Integer> percentageByOffset = Map.of(Duration.ZERO, 25, Duration.ofMinutes(60), 50, Duration.ofMinutes(120), 100);

		percentageByOffset.forEach((offset, percentage) -> {
			FlagEvaluationService service = serviceAt(T0.plus(offset));

			for (int index = 0; index < USERS; index++) {
				UserContext user = proUser(index);
				boolean inRollout = service.bucketFor(flag.getKey(), user) < percentage;

				assertThat(evaluate(service, flag, user).variationKey()).as("user %d at %s", index, offset)
						.isEqualTo(inRollout ? "on" : "off");
			}
		});
	}

	@Test
	void theSharePlacedOnTheNewVariationTracksTheStagePercentage() {
		Flag flag = flagWithActiveRollout();

		for (int percentage : new int[] { 25, 50 }) {
			Instant when = percentage == 25 ? T0 : T0.plus(Duration.ofMinutes(60));
			FlagEvaluationService service = serviceAt(when);
			long onCount = java.util.stream.IntStream.range(0, USERS)
					.filter(index -> "on".equals(evaluate(service, flag, proUser(index)).variationKey())).count();

			assertThat(onCount / (double) USERS).as("share at %d%%", percentage).isBetween((percentage - 4) / 100.0,
					(percentage + 4) / 100.0);
		}
	}

	@Test
	void everyoneInTheAudienceGetsTheNewVariationOnceTheFinalStageBegins() {
		Flag flag = flagWithActiveRollout();
		FlagEvaluationService finished = serviceAt(T0.plus(Duration.ofMinutes(120)));

		for (int index = 0; index < 500; index++) {
			assertThat(evaluate(finished, flag, proUser(index)).variationKey()).isEqualTo("on");
		}
	}

	@Test
	void aUserWhoMovedToTheNewVariationNeverMovesBackAsTimeAdvances() {
		Flag flag = flagWithActiveRollout();
		Map<Integer, Boolean> movedByUser = new HashMap<>();

		for (int minute = -5; minute <= 130; minute += 5) {
			FlagEvaluationService service = serviceAt(T0.plus(Duration.ofMinutes(minute)));

			for (int index = 0; index < 300; index++) {
				boolean onNew = "on".equals(evaluate(service, flag, proUser(index)).variationKey());

				if (movedByUser.getOrDefault(index, false)) {
					assertThat(onNew).as("user %d at minute %d", index, minute).isTrue();
				}

				movedByUser.put(index, onNew);
			}
		}
	}

	@Test
	void theSameUserAlwaysGetsTheSameVariationWithinAStage() {
		Flag flag = flagWithActiveRollout();
		UserContext user = proUser(42);
		String first = evaluate(atStart, flag, user).variationKey();

		for (int repeat = 0; repeat < 50; repeat++) {
			assertThat(evaluate(atStart, flag, user).variationKey()).isEqualTo(first);
		}
	}

	@Test
	void aUserIdRuleRollsOutOnlyToTheListedUsers() {
		Flag flag = RolloutFixtures.flag("rollout-flag");
		List<String> listed = java.util.stream.IntStream.range(0, 200).mapToObj(index -> "vip-" + index).toList();
		production(flag).getStrategies().add(userIdRule("vip-rule", 0, listed, "off"));
		production(flag).getProgressiveRollouts().add(rollout("r1", "vip-rule", "off", "on", T0));

		FlagEvaluationService service = serviceAt(T0);

		for (String userId : listed) {
			UserContext user = new UserContext(userId, Map.of());
			boolean inRollout = service.bucketFor(flag.getKey(), user) < 25;
			EvaluationResult result = evaluate(service, flag, user);

			assertThat(result.variationKey()).isEqualTo(inRollout ? "on" : "off");
			assertThat(result.reason()).isEqualTo(EvaluationReason.PROGRESSIVE_ROLLOUT);
		}

		EvaluationResult stranger = evaluate(serviceAt(T0.plus(Duration.ofDays(1))), flag, new UserContext("stranger", Map.of()));
		assertThat(stranger.reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
		assertThat(stranger.variationKey()).isEqualTo("off");
	}

	@Test
	void aPercentageRuleRollsOutInsideItsOwnAudienceOnly() {
		Flag flag = RolloutFixtures.flag("rollout-flag");
		production(flag).getStrategies().add(percentageRule("half-rule", 0, 50, "off"));
		production(flag).getProgressiveRollouts().add(rollout("r1", "half-rule", "off", "on", T0));
		FlagEvaluationService finished = serviceAt(T0.plus(Duration.ofMinutes(120)));

		for (int index = 0; index < USERS; index++) {
			UserContext user = new UserContext("user-" + index, Map.of());
			int bucket = finished.bucketFor(flag.getKey(), user);
			EvaluationResult result = evaluate(finished, flag, user);

			if (bucket < 50) {
				assertThat(result.variationKey()).isEqualTo("on");
				assertThat(result.reason()).isEqualTo(EvaluationReason.PROGRESSIVE_ROLLOUT);
			} else {
				assertThat(result.variationKey()).isEqualTo("off");
				assertThat(result.reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
			}
		}
	}

	@Test
	void aPercentageRulesAudienceIsTheIntersectionOfItsPercentageAndTheRolloutStage() {
		Flag flag = RolloutFixtures.flag("rollout-flag");
		production(flag).getStrategies().add(percentageRule("half-rule", 0, 50, "off"));
		production(flag).getProgressiveRollouts().add(rollout("r1", "half-rule", "off", "on", T0));

		for (int index = 0; index < USERS; index++) {
			UserContext user = new UserContext("user-" + index, Map.of());
			int bucket = atStart.bucketFor(flag.getKey(), user);

			// 25% stage inside a 50% audience: buckets 0-24 move, 25-49 stay on "off" via the rule, 50+ fall through.
			String expected = bucket < 25 ? "on" : "off";

			assertThat(evaluate(atStart, flag, user).variationKey()).isEqualTo(expected);
		}
	}

	@Test
	void otherRulesInTheSameEnvironmentAreNotAffected() {
		Flag flag = flagWithActiveRollout();
		production(flag).getStrategies().add(userIdRule("vip-rule", 1, List.of("vip"), "beta"));
		FlagEvaluationService finished = serviceAt(T0.plus(Duration.ofDays(1)));

		EvaluationResult result = evaluate(finished, flag, new UserContext("vip", Map.of()));

		assertThat(result.variationKey()).isEqualTo("beta");
		assertThat(result.reason()).isEqualTo(EvaluationReason.STRATEGY_MATCH);
		assertThat(result.matchedStrategyId()).isEqualTo("vip-rule");
	}

	@Test
	void ruleOrderStillDecidesWhichRuleWinsForAUserMatchingSeveral() {
		Flag flag = flagWithActiveRollout();
		production(flag).getStrategies().add(userIdRule("vip-rule", -1, List.of("user-7"), "beta"));
		FlagEvaluationService finished = serviceAt(T0.plus(Duration.ofDays(1)));

		EvaluationResult result = evaluate(finished, flag, new UserContext("user-7", Map.of("plan", "pro")));

		assertThat(result.variationKey()).isEqualTo("beta");
		assertThat(result.matchedStrategyId()).isEqualTo("vip-rule");
	}

	@Test
	void aCompletedRolloutIsHistoryAndTheRuleServesItsRewrittenVariationNormally() {
		Flag flag = flagWithRule("rollout-flag");
		rule(flag).setServedVariationKey("on");
		production(flag).getProgressiveRollouts()
				.add(ended(rollout("r1", RULE_ID, "off", "on", T0), RolloutStatus.COMPLETED, "on", 100));

		EvaluationResult result = evaluate(atStart, flag, proUser(1));

		assertThat(result.variationKey()).isEqualTo("on");
		assertThat(result.reason()).isEqualTo(EvaluationReason.STRATEGY_MATCH);
	}

	@Test
	void aStoppedRolloutIsHistoryAndTheRuleServesTheVariationChosenAtStop() {
		Flag flag = flagWithRule("rollout-flag");
		rule(flag).setServedVariationKey("beta");
		production(flag).getProgressiveRollouts()
				.add(ended(rollout("r1", RULE_ID, "off", "on", T0), RolloutStatus.STOPPED, "beta", 25));
		FlagEvaluationService later = serviceAt(T0.plus(Duration.ofDays(1)));

		for (int index = 0; index < 100; index++) {
			EvaluationResult result = evaluate(later, flag, proUser(index));

			assertThat(result.variationKey()).isEqualTo("beta");
			assertThat(result.reason()).isEqualTo(EvaluationReason.STRATEGY_MATCH);
		}
	}

	@Test
	void aDisabledEnvironmentStillServesTheOffVariation() {
		Flag flag = flagWithActiveRollout();
		production(flag).setEnabled(false);

		EvaluationResult result = evaluate(serviceAt(T0.plus(Duration.ofDays(1))), flag, proUser(1));

		assertThat(result.variationKey()).isEqualTo("off");
		assertThat(result.reason()).isEqualTo(EvaluationReason.FLAG_DISABLED);
	}

	@Test
	void aRolloutInOneEnvironmentDoesNotLeakIntoAnother() {
		Flag flag = flagWithActiveRollout();
		FlagEvaluationService finished = serviceAt(T0.plus(Duration.ofDays(1)));

		EvaluationResult staging = finished.evaluate(flag, EnvironmentKey.STAGING, proUser(1));

		assertThat(staging.variationKey()).isEqualTo("off");
		assertThat(staging.reason()).isEqualTo(EvaluationReason.STRATEGY_MATCH);
	}

	@Test
	void anAnonymousUserIsBucketedConsistentlyAndSafely() {
		Flag flag = flagWithActiveRollout();
		production(flag).getStrategies().clear();
		production(flag).getStrategies().add(percentageRule(RULE_ID, 0, 100, "off"));
		FlagEvaluationService finished = serviceAt(T0.plus(Duration.ofDays(1)));

		EvaluationResult result = evaluate(finished, flag, UserContext.anonymous(null));

		assertThat(result.variationKey()).isEqualTo("on");
	}

	@Test
	void evaluationNeverMutatesTheFlagEvenAfterTheRolloutIsDue() {
		Flag flag = flagWithActiveRollout();
		FlagEvaluationService finished = serviceAt(T0.plus(Duration.ofDays(1)));

		evaluate(finished, flag, proUser(1));

		ProgressiveRollout stored = production(flag).getProgressiveRollouts().get(0);
		assertThat(stored.getStatus()).isEqualTo(RolloutStatus.ACTIVE);
		assertThat(rule(flag).getServedVariationKey()).isEqualTo("off");
	}

	@Test
	void theDefaultConstructorEvaluatesAgainstTheSystemClock() {
		Flag flag = flagWithRule("rollout-flag");
		Instant longAgo = Instant.now().minus(Duration.ofDays(2));
		production(flag).getProgressiveRollouts().add(rollout("r1", RULE_ID, "off", "on", longAgo, stages(50, 0, 100, 60)));

		EvaluationResult result = new FlagEvaluationService().evaluate(flag, EnvironmentKey.PRODUCTION, proUser(1));

		assertThat(result.variationKey()).isEqualTo("on");
	}

	@Test
	void anAttributeRuleWithNoRolloutStillReturnsAPlainStrategyMatch() {
		Flag flag = flagWithRule("rollout-flag");
		production(flag).getStrategies().add(attributeRule("other-rule", 1, "country", "US", "on"));

		EvaluationResult result = evaluate(atStart, flag, proUser(1));

		assertThat(result.reason()).isEqualTo(EvaluationReason.STRATEGY_MATCH);
		assertThat(result.variationKey()).isEqualTo("off");
	}

	private Strategy rule(Flag flag) {
		return RolloutFixtures.rule(flag, RULE_ID);
	}
}
