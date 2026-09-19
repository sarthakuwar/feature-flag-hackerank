package com.featureflag.flags.rollout.dto;

import java.time.Instant;
import java.util.List;

import com.featureflag.flags.ProgressiveRollout;
import com.featureflag.flags.RolloutStage;
import com.featureflag.flags.RolloutStatus;

/**
 * A rollout plus everything a UI needs to draw its progress without re-deriving the schedule:
 * where it is right now (percentage and stage), when the next stage lands, and when it is due to
 * complete. Ended rollouts report the percentage they finished or were stopped at.
 */
public record ProgressiveRolloutResponse(String id, String ruleId, String strategyId, String fromVariationKey,
		String toVariationKey, RolloutStatus status, List<RolloutStage> stages, Instant startAt, Instant createdAt,
		String startedBy, Instant endedAt, String endedBy, String resolvedVariationKey, int currentPercentage,
		int currentStageIndex, Instant nextStageAt, Instant completesAt) {

	public static ProgressiveRolloutResponse from(ProgressiveRollout rollout, Instant now) {
		boolean active = rollout.active();
		int percentage = active ? rollout.percentageAt(now)
				: rollout.getFinalPercentage() == null ? 0 : rollout.getFinalPercentage();
		int stageIndex = active ? rollout.stageIndexAt(now) : -1;
		Instant nextStageAt = active ? rollout.nextStageAt(now).orElse(null) : null;

		return new ProgressiveRolloutResponse(rollout.getId(), rollout.getRuleId(), rollout.getStrategyId(),
				rollout.getFromVariationKey(), rollout.getToVariationKey(), rollout.getStatus(), rollout.getStages(), rollout.getStartAt(),
				rollout.getCreatedAt(), rollout.getStartedBy(), rollout.getEndedAt(), rollout.getEndedBy(),
				rollout.getResolvedVariationKey(), percentage, stageIndex, nextStageAt, rollout.completesAt());
	}
}
