package com.featureflag.flags;

/**
 * {@code includedInExperiment} says whether this evaluation counts toward the flag's experiment
 * metrics. Only randomly assigned traffic does: an experiment split, a percentage rollout, or a
 * rollout rule that opted in (see {@link com.featureflag.flags.targeting.TargetingRule}). Everything
 * else stays out so it cannot skew the comparison between variations: hand-picked audiences
 * (individual targets, fixed-variation rules, user-id and attribute strategies), progressive-rollout
 * ramp traffic, and users who were never assigned a variation (flag disabled, prerequisite failed,
 * fallthrough).
 *
 * <p>{@code failedPrerequisiteKey} is set only for {@link EvaluationReason#PREREQUISITE_FAILED}.
 */
public record EvaluationResult(String flagKey, String variationKey, String value, EvaluationReason reason,
		String matchedStrategyId, String matchedRuleId, boolean includedInExperiment, String failedPrerequisiteKey) {

	public static EvaluationResult flagDisabled(String flagKey, String variationKey, String value) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.FLAG_DISABLED, null, null, false, null);
	}

	/** The first prerequisite that was missing, archived, disabled, or itself unmet. */
	public static EvaluationResult prerequisiteFailed(String flagKey, String variationKey, String value,
			String failedPrerequisiteKey) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.PREREQUISITE_FAILED, null, null, false,
				failedPrerequisiteKey);
	}

	/** Individual targets are never part of an experiment. */
	public static EvaluationResult individualTarget(String flagKey, String variationKey, String value) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.INDIVIDUAL_TARGET, null, null, false, null);
	}

	public static EvaluationResult ruleMatch(String flagKey, String variationKey, String value, String ruleId,
			boolean includedInExperiment) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.RULE_MATCH, null, ruleId,
				includedInExperiment, null);
	}

	/** A user-id or attribute strategy serves a hand-picked audience, so it stays out of the experiment. */
	public static EvaluationResult strategyMatch(String flagKey, String variationKey, String value, String strategyId) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.STRATEGY_MATCH, strategyId, null, false, null);
	}

	public static EvaluationResult rolloutEnabled(String flagKey, String variationKey, String value) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.ROLLOUT_ENABLED, null, null, true, null);
	}

	public static EvaluationResult experimentAssigned(String flagKey, String variationKey, String value) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.EXPERIMENT_ASSIGNED, null, null, true, null);
	}

	/** Ramp traffic of a rollout on a targeting rule. */
	public static EvaluationResult progressiveRuleRollout(String flagKey, String variationKey, String value, String ruleId) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.PROGRESSIVE_ROLLOUT, null, ruleId, false,
				null);
	}

	/** Ramp traffic of a rollout on a legacy strategy. */
	public static EvaluationResult progressiveRollout(String flagKey, String variationKey, String value, String strategyId) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.PROGRESSIVE_ROLLOUT, strategyId, null, false,
				null);
	}

	public static EvaluationResult fallthrough(String flagKey, String variationKey, String value) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.FALLTHROUGH, null, null, false, null);
	}
}
