package com.featureflag.flags;

import static com.featureflag.flags.rollout.RolloutFixtures.T0;
import static com.featureflag.flags.rollout.RolloutFixtures.experimentRule;
import static com.featureflag.flags.rollout.RolloutFixtures.flag;
import static com.featureflag.flags.rollout.RolloutFixtures.percentageRule;
import static com.featureflag.flags.rollout.RolloutFixtures.production;
import static com.featureflag.flags.rollout.RolloutFixtures.rollout;
import static com.featureflag.flags.rollout.RolloutFixtures.ruleRollout;
import static com.featureflag.flags.rollout.RolloutFixtures.targetingRule;
import static com.featureflag.flags.rollout.RolloutFixtures.userIdRule;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.featureflag.flags.targeting.IndividualTarget;
import com.featureflag.flags.targeting.TargetingLookup;
import com.featureflag.flags.targeting.TargetingRule;

/**
 * The single rule for {@link EvaluationResult#includedInExperiment()}: only randomly assigned traffic
 * counts (experiment split, percentage rollout, an opted-in rollout rule). Every result path is one
 * row of the table, and a new {@link EvaluationReason} fails the coverage test until it gets a row.
 */
class FlagEvaluationExperimentInclusionTest {

	private static final EnvironmentKey PROD = EnvironmentKey.PRODUCTION;

	private static final UserContext USER = new UserContext("user-1", Map.of("plan", "pro"));

	private record Scenario(String name, EvaluationReason reason, boolean included, Supplier<Flag> flag) {

		@Override
		public String toString() {
			return name;
		}
	}

	private final FlagEvaluationService service = new FlagEvaluationService(TargetingLookup.EMPTY, FlagLookup.EMPTY,
			Clock.fixed(T0, ZoneOffset.UTC));

	private static Stream<Scenario> scenarios() {
		return Stream.of(
				new Scenario("flag disabled", EvaluationReason.FLAG_DISABLED, false, () -> {
					Flag flag = flag("f");
					production(flag).setEnabled(false);

					return flag;
				}),
				new Scenario("prerequisite failed", EvaluationReason.PREREQUISITE_FAILED, false, () -> {
					Flag flag = flag("f");
					flag.setDependsOn(new ArrayList<>(List.of("missing")));

					return flag;
				}),
				new Scenario("individual target", EvaluationReason.INDIVIDUAL_TARGET, false, () -> {
					Flag flag = flag("f");
					production(flag).getIndividualTargets().add(new IndividualTarget("user", "on", Set.of("user-1")));

					return flag;
				}),
				new Scenario("fixed-variation rule", EvaluationReason.RULE_MATCH, false, () -> withRule(fixedRule())),
				new Scenario("rollout rule that did not opt in", EvaluationReason.RULE_MATCH, false, () -> withRule(rolloutRule(false))),
				new Scenario("rollout rule that opted in", EvaluationReason.RULE_MATCH, true, () -> withRule(rolloutRule(true))),
				new Scenario("user id strategy", EvaluationReason.STRATEGY_MATCH, false, () -> {
					Flag flag = flag("f");
					production(flag).getStrategies().add(userIdRule("s1", 0, List.of("user-1"), "on"));

					return flag;
				}),
				new Scenario("percentage rollout strategy", EvaluationReason.ROLLOUT_ENABLED, true, () -> {
					Flag flag = flag("f");
					production(flag).getStrategies().add(percentageRule("s1", 0, 100, "on"));

					return flag;
				}),
				new Scenario("experiment split", EvaluationReason.EXPERIMENT_ASSIGNED, true, () -> {
					Flag flag = flag("f");
					production(flag).getStrategies().add(experimentRule("s1", 0));

					return flag;
				}),
				new Scenario("progressive rollout on a strategy", EvaluationReason.PROGRESSIVE_ROLLOUT, false, () -> {
					Flag flag = flag("f");
					production(flag).getStrategies().add(percentageRule("s1", 0, 100, "off"));
					production(flag).getProgressiveRollouts().add(rollout("r1", "s1", "off", "on", T0));

					return flag;
				}),
				new Scenario("progressive rollout on a rule", EvaluationReason.PROGRESSIVE_ROLLOUT, false, () -> {
					Flag flag = withRule(fixedRule());
					production(flag).getProgressiveRollouts().add(ruleRollout("r1", "rule-1", "off", "on", T0));

					return flag;
				}),
				new Scenario("fallthrough", EvaluationReason.FALLTHROUGH, false, () -> flag("f")));
	}

	private static TargetingRule fixedRule() {
		return targetingRule("rule-1", "plan", "pro", "off");
	}

	private static TargetingRule rolloutRule(boolean includeInExperiment) {
		TargetingRule rule = targetingRule("rule-1", "plan", "pro", null);
		rule.setRolloutWeights(new LinkedHashMap<>(Map.of("off", 50, "on", 50)));
		rule.setIncludeInExperiment(includeInExperiment);

		return rule;
	}

	private static Flag withRule(TargetingRule rule) {
		Flag flag = flag("f");
		production(flag).getRules().add(rule);

		return flag;
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("scenarios")
	void eachResultKindFollowsTheInclusionRule(Scenario scenario) {
		EvaluationResult result = service.evaluate(scenario.flag().get(), PROD, USER);

		assertThat(result.reason()).isEqualTo(scenario.reason());
		assertThat(result.includedInExperiment()).isEqualTo(scenario.included());
	}

	@Test
	void everyEvaluationReasonHasARowInTheTable() {
		Set<EvaluationReason> covered = EnumSet.noneOf(EvaluationReason.class);
		scenarios().forEach(scenario -> covered.add(scenario.reason()));

		assertThat(covered).containsExactlyInAnyOrder(EvaluationReason.values());
	}
}
