package com.featureflag.flags.rollout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagLifecycle;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.ProgressiveRollout;
import com.featureflag.flags.RolloutStage;
import com.featureflag.flags.RolloutStatus;
import com.featureflag.flags.Strategy;
import com.featureflag.flags.StrategyType;
import com.featureflag.flags.Variation;
import com.featureflag.flags.targeting.Clause;
import com.featureflag.flags.targeting.ClauseOperator;
import com.featureflag.flags.targeting.TargetingRule;

/** Builders shared by the progressive rollout unit tests. */
public final class RolloutFixtures {

	public static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

	public static final String RULE_ID = "rule-1";

	private RolloutFixtures() {
	}

	/** A multivariate flag (off, on, beta) with every environment enabled and no rules. */
	public static Flag flag(String key) {
		Flag flag = new Flag();
		flag.setKey(key);
		flag.setName("Rollout Flag");
		flag.setType(FlagType.MULTIVARIATE);
		flag.setLifecycle(FlagLifecycle.ACTIVE);
		flag.setOwner("owner");
		flag.setDependsOn(new ArrayList<>());
		flag.setVariations(new ArrayList<>(List.of(new Variation("off", "Off", "false"), new Variation("on", "On", "true"),
				new Variation("beta", "Beta", "beta"))));

		Map<EnvironmentKey, EnvironmentConfig> environments = new EnumMap<>(EnvironmentKey.class);

		for (EnvironmentKey environment : EnvironmentKey.values()) {
			environments.put(environment, new EnvironmentConfig(true, "off", new ArrayList<>()));
		}

		flag.setEnvironments(environments);

		return flag;
	}

	/** {@link #flag} with a single attribute rule {@link #RULE_ID} (plan = pro) serving "off" in every environment. */
	public static Flag flagWithRule(String key) {
		Flag flag = flag(key);

		flag.getEnvironments().values().forEach(config -> config.getStrategies().add(attributeRule(RULE_ID, 0, "plan", "pro", "off")));

		return flag;
	}

	public static EnvironmentConfig production(Flag flag) {
		return flag.getEnvironments().get(EnvironmentKey.PRODUCTION);
	}

	public static Strategy rule(Flag flag, String id) {
		return production(flag).getStrategies().stream().filter(strategy -> id.equals(strategy.getId())).findFirst().orElseThrow();
	}

	public static Strategy attributeRule(String id, int priority, String attribute, String value, String served) {
		Strategy strategy = new Strategy();
		strategy.setId(id);
		strategy.setPriority(priority);
		strategy.setType(StrategyType.ATTRIBUTE);
		strategy.setAttribute(attribute);
		strategy.setAttributeValue(value);
		strategy.setServedVariationKey(served);

		return strategy;
	}

	public static Strategy userIdRule(String id, int priority, List<String> userIds, String served) {
		Strategy strategy = new Strategy();
		strategy.setId(id);
		strategy.setPriority(priority);
		strategy.setType(StrategyType.USER_ID);
		strategy.setUserIds(userIds);
		strategy.setServedVariationKey(served);

		return strategy;
	}

	public static Strategy percentageRule(String id, int priority, int percentage, String served) {
		Strategy strategy = new Strategy();
		strategy.setId(id);
		strategy.setPriority(priority);
		strategy.setType(StrategyType.PERCENTAGE_ROLLOUT);
		strategy.setPercentage(percentage);
		strategy.setServedVariationKey(served);

		return strategy;
	}

	public static Strategy experimentRule(String id, int priority) {
		Strategy strategy = new Strategy();
		strategy.setId(id);
		strategy.setPriority(priority);
		strategy.setType(StrategyType.EXPERIMENT_SPLIT);
		strategy.setVariationWeights(new LinkedHashMap<>(Map.of("off", 34, "on", 33, "beta", 33)));

		return strategy;
	}

	/** Stages from flat (percentage, offsetMinutes) pairs: {@code stages(25, 0, 50, 60, 100, 120)}. */
	public static List<RolloutStage> stages(int... pairs) {
		List<RolloutStage> stages = new ArrayList<>();

		for (int index = 0; index < pairs.length; index += 2) {
			stages.add(new RolloutStage(pairs[index], pairs[index + 1]));
		}

		return stages;
	}

	/** 25% at start, 50% after an hour, 100% after two. */
	public static ProgressiveRollout rollout(String id, String strategyId, String from, String to, Instant startAt) {
		return rollout(id, strategyId, from, to, startAt, stages(25, 0, 50, 60, 100, 120));
	}

	public static ProgressiveRollout rollout(String id, String strategyId, String from, String to, Instant startAt,
			List<RolloutStage> stages) {
		ProgressiveRollout rollout = new ProgressiveRollout();
		rollout.setId(id);
		rollout.setStrategyId(strategyId);
		rollout.setFromVariationKey(from);
		rollout.setToVariationKey(to);
		rollout.setStages(new ArrayList<>(stages));
		rollout.setStatus(RolloutStatus.ACTIVE);
		rollout.setStartAt(startAt);
		rollout.setCreatedAt(startAt);
		rollout.setStartedBy("tester");

		return rollout;
	}

	// ---- targeting-rule targets (as opposed to the legacy strategies above) ----

	public static final String TARGETING_RULE_ID = "targeting-rule-1";

	/** A targeting rule matching users whose {@code attribute} is {@code value}, serving one fixed variation. */
	public static TargetingRule targetingRule(String id, String attribute, String value, String served) {
		TargetingRule rule = new TargetingRule();
		rule.setId(id);
		rule.setServedVariationKey(served);
		rule.setClauses(new ArrayList<>(List.of(new Clause("user", attribute, ClauseOperator.IN, List.of(value), false))));

		return rule;
	}

	/** {@link #flag} with a single targeting rule {@link #TARGETING_RULE_ID} (plan = pro) serving "off" in every environment. */
	public static Flag flagWithTargetingRule(String key) {
		Flag flag = flag(key);

		flag.getEnvironments().values()
				.forEach(config -> config.getRules().add(targetingRule(TARGETING_RULE_ID, "plan", "pro", "off")));

		return flag;
	}

	public static TargetingRule targetingRuleOf(Flag flag, String id) {
		return production(flag).getRules().stream().filter(rule -> id.equals(rule.getId())).findFirst().orElseThrow();
	}

	/** 25% at start, 50% after an hour, 100% after two, ramping a targeting rule rather than a strategy. */
	public static ProgressiveRollout ruleRollout(String id, String ruleId, String from, String to, Instant startAt) {
		return ruleRollout(id, ruleId, from, to, startAt, stages(25, 0, 50, 60, 100, 120));
	}

	public static ProgressiveRollout ruleRollout(String id, String ruleId, String from, String to, Instant startAt,
			List<RolloutStage> stages) {
		ProgressiveRollout rollout = rollout(id, null, from, to, startAt, stages);
		rollout.setRuleId(ruleId);

		return rollout;
	}

	/** Marks a rollout as ended the way the service does, without running the service. */
	public static ProgressiveRollout ended(ProgressiveRollout rollout, RolloutStatus status, String resolved, int percentage) {
		rollout.setStatus(status);
		rollout.setEndedAt(rollout.getStartAt().plusSeconds(3600));
		rollout.setEndedBy(status == RolloutStatus.COMPLETED ? "system:rollout" : "tester");
		rollout.setResolvedVariationKey(resolved);
		rollout.setFinalPercentage(percentage);

		return rollout;
	}
}
