package com.featureflag.flags;

public record EvaluationResult(String flagKey, String variationKey, String value, EvaluationReason reason,
		String matchedStrategyId) {

	public static EvaluationResult strategyMatch(String flagKey, String variationKey, String value, String strategyId) {
		return new EvaluationResult(flagKey, variationKey, value, EvaluationReason.STRATEGY_MATCH, strategyId);
	}

	public static EvaluationResult of(String flagKey, String variationKey, String value, EvaluationReason reason) {
		return new EvaluationResult(flagKey, variationKey, value, reason, null);
	}
}
