package com.featureflag.flags;

/**
 * {@code includedInExperiment} says whether this evaluation should count toward the flag's experiment
 * metrics. Everything does except individual targets and rules that weren't opted in (see
 * {@link com.featureflag.flags.targeting.TargetingRule}): those users were hand-picked rather than
 * randomly assigned, so counting them would skew the comparison between variations.
 */
public record EvaluationResult(String flagKey, String variationKey, String value, EvaluationReason reason,
		String matchedStrategyId, String matchedRuleId, boolean includedInExperiment) {

	public EvaluationResult(String flagKey, String variationKey, String value, EvaluationReason reason,
			String matchedStrategyId, String matchedRuleId) {
		this(flagKey, variationKey, value, reason, matchedStrategyId, matchedRuleId, true);
	}

	public EvaluationResult(String flagKey, String variationKey, String value, EvaluationReason reason,
			String matchedStrategyId) {
		this(flagKey, variationKey, value, reason, matchedStrategyId, null, true);
	}

	public static EvaluationResult strategyMatch(String flagKey, String variationKey, String value, String strategyId) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.STRATEGY_MATCH, strategyId, null, true);
	}

	public static EvaluationResult ruleMatch(String flagKey, String variationKey, String value, String ruleId,
			boolean includedInExperiment) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.RULE_MATCH, null, ruleId,
				includedInExperiment);
	}

	/** Individual targets are never part of an experiment. */
	public static EvaluationResult individualTarget(String flagKey, String variationKey, String value) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.INDIVIDUAL_TARGET, null, null, false);
	}

	/**
	 * A user in a targeting rule's audience that has an active progressive rollout. The rule's audience
	 * is hand-picked, not randomly assigned, so like any fixed-variation rule this traffic stays out of
	 * experiment metrics.
	 */
	public static EvaluationResult progressiveRuleRollout(String flagKey, String variationKey, String value, String ruleId) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.PROGRESSIVE_ROLLOUT, null, ruleId, false);
	}

	public static EvaluationResult progressiveRollout(String flagKey, String variationKey, String value, String strategyId) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.PROGRESSIVE_ROLLOUT, strategyId);
	}

	public static EvaluationResult of(String flagKey, String variationKey, String value, EvaluationReason reason) {
		return new EvaluationResult(flagKey, variationKey, value, reason, null, null, true);
	}
}
