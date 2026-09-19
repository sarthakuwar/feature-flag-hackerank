package com.featureflag.flags.rollout.dto;

import java.time.Instant;
import java.util.List;

/**
 * Either {@code stages} (an explicit schedule) or {@code steps} with {@code durationMinutes} (an
 * evenly spaced ramp the server expands into stages) - never both. {@code startAt} is optional and
 * defaults to now; a future value schedules the rollout to begin later (0% until then).
 */
public record StartProgressiveRolloutRequest(String strategyId, String toVariationKey, Instant startAt,
		List<RolloutStageRequest> stages, Integer steps, Integer durationMinutes, String changedBy) {
}
