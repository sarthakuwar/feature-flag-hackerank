package com.featureflag.flags.rollout.dto;

import java.time.Instant;
import java.util.List;

/**
 * Name what to ramp with exactly one of {@code ruleId} (a targeting rule that serves one fixed
 * variation) or {@code strategyId} (a legacy activation strategy). Then give either {@code stages} (an
 * explicit schedule) or {@code steps} with {@code durationMinutes} (an evenly spaced ramp the server
 * expands into stages) - never both. {@code startAt} is optional and defaults to now; a future value
 * schedules the rollout to begin later (0% until then).
 */
public record StartProgressiveRolloutRequest(String strategyId, String toVariationKey, Instant startAt,
		List<RolloutStageRequest> stages, Integer steps, Integer durationMinutes, String changedBy, String ruleId) {

	/** The original strategy-targeting form, kept so existing callers need no change. */
	public StartProgressiveRolloutRequest(String strategyId, String toVariationKey, Instant startAt,
			List<RolloutStageRequest> stages, Integer steps, Integer durationMinutes, String changedBy) {
		this(strategyId, toVariationKey, startAt, stages, steps, durationMinutes, changedBy, null);
	}

	/** Ramp a targeting rule. */
	public static StartProgressiveRolloutRequest forRule(String ruleId, String toVariationKey, Instant startAt,
			List<RolloutStageRequest> stages, Integer steps, Integer durationMinutes, String changedBy) {
		return new StartProgressiveRolloutRequest(null, toVariationKey, startAt, stages, steps, durationMinutes, changedBy, ruleId);
	}
}
