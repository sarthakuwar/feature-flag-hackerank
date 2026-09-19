package com.featureflag.flags.rollout;

import static com.featureflag.flags.rollout.RolloutFixtures.T0;
import static com.featureflag.flags.rollout.RolloutFixtures.TARGETING_RULE_ID;
import static com.featureflag.flags.rollout.RolloutFixtures.attributeRule;
import static com.featureflag.flags.rollout.RolloutFixtures.ended;
import static com.featureflag.flags.rollout.RolloutFixtures.flagWithTargetingRule;
import static com.featureflag.flags.rollout.RolloutFixtures.production;
import static com.featureflag.flags.rollout.RolloutFixtures.ruleRollout;
import static com.featureflag.flags.rollout.RolloutFixtures.stages;
import static com.featureflag.flags.rollout.RolloutFixtures.targetingRule;
import static com.featureflag.flags.rollout.RolloutFixtures.targetingRuleOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.EvaluationReason;
import com.featureflag.flags.EvaluationResult;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagEvaluationService;
import com.featureflag.flags.RolloutStatus;
import com.featureflag.flags.UserContext;
import com.featureflag.flags.targeting.IndividualTarget;
import com.featureflag.shared.MutableClock;

/**
 * A progressive rollout ramping a targeting rule: the rule's audience moves from the variation the
 * rule serves ("off") to the rollout's target ("on") as the schedule advances, users outside the
 * audience are untouched, and the usual precedence (individual targets, rule order, disabled rules)
 * still applies. Time is a {@link MutableClock} moved by hand.
 */
class RuleProgressiveRolloutEvaluationTest {

	private static final EnvironmentKey PROD = EnvironmentKey.PRODUCTION;

	private MutableClock clock;

	private FlagEvaluationService service;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(T0);
		service = new FlagEvaluationService(clock);
	}

	/** {@link RolloutFixtures#flagWithTargetingRule} with a 25% -> 50% (after 1h) -> 100% (after 2h) ramp already running. */
	private Flag rampingFlag() {
		Flag flag = flagWithTargetingRule("ramp");
		production(flag).getProgressiveRollouts().add(ruleRollout("r1", TARGETING_RULE_ID, "off", "on", T0));

		return flag;
	}

	private EvaluationResult evaluate(Flag flag, UserContext user) {
		return service.evaluate(flag, PROD, user);
	}

	private static UserContext pro(String userId) {
		return new UserContext(userId, Map.of("plan", "pro"));
	}

	/** A pro user whose stable bucket for this flag is exactly {@code bucket}. */
	private UserContext proUserInBucket(Flag flag, int bucket) {
		for (int index = 0; index < 100_000; index++) {
			UserContext candidate = pro("bucket-user-" + index);

			if (service.bucketFor(flag.getKey(), candidate) == bucket) {
				return candidate;
			}
		}

		throw new IllegalStateException("no user found for bucket " + bucket);
	}

	// ---- the ramp itself ----

	@Test
	void theRulesAudienceIsSplitAtTheCurrentPercentageAndReportedAsAProgressiveRollout() {
		Flag flag = rampingFlag();
		int moved = 0;

		for (int index = 0; index < 1_000; index++) {
			EvaluationResult result = evaluate(flag, pro("user-" + index));

			assertThat(result.reason()).isEqualTo(EvaluationReason.PROGRESSIVE_ROLLOUT);

			if (result.variationKey().equals("on")) {
				moved++;
			}
		}

		assertThat(moved).as("about 25% of the audience at the first stage").isBetween(180, 320);
	}

	@Test
	void aUsersBucketDecidesWhichSideOfTheRampTheyAreOnAndTheBoundaryIsExclusive() {
		Flag flag = rampingFlag();

		assertThat(evaluate(flag, proUserInBucket(flag, 0)).variationKey()).isEqualTo("on");
		assertThat(evaluate(flag, proUserInBucket(flag, 24)).variationKey()).as("bucket 24 < 25%").isEqualTo("on");
		assertThat(evaluate(flag, proUserInBucket(flag, 25)).variationKey()).as("bucket 25 is not < 25%").isEqualTo("off");
		assertThat(evaluate(flag, proUserInBucket(flag, 99)).variationKey()).isEqualTo("off");
	}

	@Test
	void theResultNamesTheRuleNotAStrategyAndStaysOutOfExperimentMetrics() {
		EvaluationResult result = evaluate(rampingFlag(), pro("anyone"));

		assertThat(result.matchedRuleId()).isEqualTo(TARGETING_RULE_ID);
		assertThat(result.matchedStrategyId()).isNull();
		assertThat(result.includedInExperiment()).as("a hand-picked audience is not a randomized comparison").isFalse();
	}

	@Test
	void raisingThePercentageOnlyEverMovesUsersForwardNeverBack() {
		Flag flag = rampingFlag();
		int previouslyMoved = 0;
		java.util.Set<String> movedEarlier = new java.util.HashSet<>();

		for (int stage = 0; stage < 3; stage++) {
			clock.set(T0.plus(Duration.ofMinutes(stage * 60L)));
			int movedNow = 0;

			for (int index = 0; index < 500; index++) {
				String userId = "user-" + index;
				boolean isOn = evaluate(flag, pro(userId)).variationKey().equals("on");

				if (movedEarlier.contains(userId)) {
					assertThat(isOn).as(userId + " moved earlier and must not move back at stage " + stage).isTrue();
				}

				if (isOn) {
					movedNow++;
					movedEarlier.add(userId);
				}
			}

			assertThat(movedNow).as("stage " + stage).isGreaterThanOrEqualTo(previouslyMoved);
			previouslyMoved = movedNow;
		}

		assertThat(previouslyMoved).as("at the final 100% stage everyone has moved").isEqualTo(500);
	}

	@Test
	void theSameUserAlwaysGetsTheSameAnswerAtTheSameTime() {
		Flag flag = rampingFlag();

		for (int index = 0; index < 100; index++) {
			UserContext user = pro("stable-" + index);
			String first = evaluate(flag, user).variationKey();

			assertThat(evaluate(flag, user).variationKey()).isEqualTo(first);
		}
	}

	@Test
	void everyoneKeepsTheOldVariationUntilTheScheduledStartTime() {
		Flag flag = flagWithTargetingRule("scheduled");
		production(flag).getProgressiveRollouts()
				.add(ruleRollout("r1", TARGETING_RULE_ID, "off", "on", T0.plus(Duration.ofHours(1))));

		for (int index = 0; index < 200; index++) {
			EvaluationResult result = evaluate(flag, pro("user-" + index));

			assertThat(result.variationKey()).isEqualTo("off");
			assertThat(result.reason()).as("still a progressive rollout, just at 0%").isEqualTo(EvaluationReason.PROGRESSIVE_ROLLOUT);
		}
	}

	// ---- who the ramp applies to ----

	@Test
	void usersOutsideTheRulesAudienceAreUntouchedByTheRamp() {
		Flag flag = rampingFlag();

		EvaluationResult free = evaluate(flag, new UserContext("free-user", Map.of("plan", "free")));

		assertThat(free.reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
		assertThat(free.matchedRuleId()).isNull();
	}

	@Test
	void aRampOnOneRuleDoesNotAffectAnotherRule() {
		Flag flag = rampingFlag();
		production(flag).getRules().add(targetingRule("other-rule", "country", "US", "beta"));

		EvaluationResult other = evaluate(flag, new UserContext("us-user", Map.of("country", "US")));

		assertThat(other.reason()).isEqualTo(EvaluationReason.RULE_MATCH);
		assertThat(other.matchedRuleId()).isEqualTo("other-rule");
		assertThat(other.variationKey()).isEqualTo("beta");
	}

	@Test
	void anEarlierRuleStillWinsOverTheRampedRuleForUsersBothMatch() {
		Flag flag = rampingFlag();
		production(flag).getRules().add(0, targetingRule("earlier", "tier", "vip", "beta"));

		EvaluationResult vipPro = evaluate(flag, new UserContext("vip", Map.of("plan", "pro", "tier", "vip")));

		assertThat(vipPro.matchedRuleId()).isEqualTo("earlier");
		assertThat(vipPro.reason()).isEqualTo(EvaluationReason.RULE_MATCH);
	}

	@Test
	void anIndividualTargetStillWinsOverTheRamp() {
		Flag flag = rampingFlag();
		production(flag).getIndividualTargets().add(new IndividualTarget("user", "beta", java.util.Set.of("vip-user")));

		EvaluationResult result = evaluate(flag, pro("vip-user"));

		assertThat(result.reason()).isEqualTo(EvaluationReason.INDIVIDUAL_TARGET);
		assertThat(result.variationKey()).isEqualTo("beta");
	}

	@Test
	void aDisabledRuleServesNobodyEvenWhileItsRolloutIsActive() {
		Flag flag = rampingFlag();
		targetingRuleOf(flag, TARGETING_RULE_ID).setEnabled(false);

		EvaluationResult result = evaluate(flag, pro("user-1"));

		assertThat(result.reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
		assertThat(result.matchedRuleId()).isNull();
	}

	@Test
	void aRuleThatItselfServesAPercentageRolloutIsNotRampedEvenIfARolloutRecordPointsAtIt() {
		Flag flag = rampingFlag();
		targetingRuleOf(flag, TARGETING_RULE_ID).setServedVariationKey(null);
		targetingRuleOf(flag, TARGETING_RULE_ID).setRolloutWeights(new LinkedHashMap<>(Map.of("off", 100, "on", 0)));

		EvaluationResult result = evaluate(flag, pro("user-1"));

		assertThat(result.reason()).isEqualTo(EvaluationReason.RULE_MATCH);
		assertThat(result.variationKey()).isEqualTo("off");
	}

	// ---- once it ends ----

	@Test
	void aCompletedRolloutIsHistoryAndTheRuleServesItsRewrittenVariationToEveryone() {
		Flag flag = rampingFlag();
		ended(production(flag).getProgressiveRollouts().get(0), RolloutStatus.COMPLETED, "on", 100);
		targetingRuleOf(flag, TARGETING_RULE_ID).setServedVariationKey("on");

		for (int index = 0; index < 100; index++) {
			EvaluationResult result = evaluate(flag, pro("user-" + index));

			assertThat(result.reason()).isEqualTo(EvaluationReason.RULE_MATCH);
			assertThat(result.variationKey()).isEqualTo("on");
		}
	}

	@Test
	void aStoppedRolloutIsHistoryAndTheRuleServesWhateverItWasStoppedOn() {
		Flag flag = rampingFlag();
		ended(production(flag).getProgressiveRollouts().get(0), RolloutStatus.STOPPED, "off", 25);

		EvaluationResult result = evaluate(flag, pro("user-1"));

		assertThat(result.reason()).isEqualTo(EvaluationReason.RULE_MATCH);
		assertThat(result.variationKey()).isEqualTo("off");
	}

	// ---- regression: strategies alongside a rule rollout ----

	@Test
	void aRuleRolloutDoesNotBreakEvaluationOfLegacyStrategiesInTheSameEnvironment() {
		Flag flag = rampingFlag();
		production(flag).getStrategies().add(attributeRule("legacy-1", 0, "country", "US", "beta"));

		// Looking up "is there a rollout for this strategy" used to dereference the rollout's strategy id,
		// which is null for a rule rollout, and would have thrown here.
		EvaluationResult result = evaluate(flag, new UserContext("us-user", Map.of("country", "US")));

		assertThat(result.reason()).isEqualTo(EvaluationReason.STRATEGY_MATCH);
		assertThat(result.variationKey()).isEqualTo("beta");
		assertThat(production(flag).activeRolloutFor("legacy-1")).isEmpty();
		assertThat(production(flag).activeRolloutForRule(TARGETING_RULE_ID)).isPresent();
	}

	@Test
	void aStrategyRolloutAndARuleWithTheSameIdAreNotConfused() {
		Flag flag = flagWithTargetingRule("same-id");
		production(flag).getStrategies().add(attributeRule(TARGETING_RULE_ID, 0, "country", "US", "beta"));
		production(flag).getProgressiveRollouts().add(ruleRollout("r1", TARGETING_RULE_ID, "off", "on", T0, stages(100, 0)));

		EvaluationResult strategyUser = evaluate(flag, new UserContext("us", Map.of("country", "US")));

		assertThat(strategyUser.reason()).as("the strategy is not the ramped target").isEqualTo(EvaluationReason.STRATEGY_MATCH);
		assertThat(strategyUser.variationKey()).isEqualTo("beta");
		assertThat(evaluate(flag, pro("pro-user")).reason()).isEqualTo(EvaluationReason.PROGRESSIVE_ROLLOUT);
	}
}
