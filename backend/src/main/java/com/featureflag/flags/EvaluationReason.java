package com.featureflag.flags;

public enum EvaluationReason {
	FLAG_DISABLED,
	STRATEGY_MATCH,
	ROLLOUT_ENABLED,
	FALLTHROUGH
}
