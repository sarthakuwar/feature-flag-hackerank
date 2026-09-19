package com.featureflag.flags;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Gradually moves a targeting rule's audience from the variation it serves today
 * ({@link #fromVariationKey}) to {@link #toVariationKey} on a fixed schedule. The schedule lives on
 * the flag document itself and {@link #percentageAt} is a pure function of it, so evaluation stays
 * an in-memory computation against the cached flag - nothing has to be written for traffic to
 * advance from one stage to the next. Users are assigned by the same stable hash bucket every
 * other percentage strategy uses, so raising the percentage only ever moves users from
 * {@code from} to {@code to}, never back and never reshuffled.
 *
 * <p>An ACTIVE rollout is the only kind evaluation looks at. COMPLETED and STOPPED rollouts are
 * kept as history: when one ends, the rule's servedVariationKey is rewritten to whatever it now
 * permanently serves, so the rule needs no help from the rollout record any more.
 */
public class ProgressiveRollout {

	private String id;

	private String strategyId;

	private String fromVariationKey;

	private String toVariationKey;

	private List<RolloutStage> stages = new ArrayList<>();

	private RolloutStatus status = RolloutStatus.ACTIVE;

	private Instant startAt;

	private Instant createdAt;

	private String startedBy;

	private Instant endedAt;

	private String endedBy;

	/** Set when the rollout ends: the variation the rule serves from then on. */
	private String resolvedVariationKey;

	/** Set when the rollout ends: the share of the audience that had been moved to {@code to}. */
	private Integer finalPercentage;

	public ProgressiveRollout() {
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getStrategyId() {
		return strategyId;
	}

	public void setStrategyId(String strategyId) {
		this.strategyId = strategyId;
	}

	public String getFromVariationKey() {
		return fromVariationKey;
	}

	public void setFromVariationKey(String fromVariationKey) {
		this.fromVariationKey = fromVariationKey;
	}

	public String getToVariationKey() {
		return toVariationKey;
	}

	public void setToVariationKey(String toVariationKey) {
		this.toVariationKey = toVariationKey;
	}

	public List<RolloutStage> getStages() {
		return stages;
	}

	public void setStages(List<RolloutStage> stages) {
		this.stages = stages;
	}

	public RolloutStatus getStatus() {
		return status;
	}

	public void setStatus(RolloutStatus status) {
		this.status = status;
	}

	public Instant getStartAt() {
		return startAt;
	}

	public void setStartAt(Instant startAt) {
		this.startAt = startAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public String getStartedBy() {
		return startedBy;
	}

	public void setStartedBy(String startedBy) {
		this.startedBy = startedBy;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public void setEndedAt(Instant endedAt) {
		this.endedAt = endedAt;
	}

	public String getEndedBy() {
		return endedBy;
	}

	public void setEndedBy(String endedBy) {
		this.endedBy = endedBy;
	}

	public String getResolvedVariationKey() {
		return resolvedVariationKey;
	}

	public void setResolvedVariationKey(String resolvedVariationKey) {
		this.resolvedVariationKey = resolvedVariationKey;
	}

	public Integer getFinalPercentage() {
		return finalPercentage;
	}

	public void setFinalPercentage(Integer finalPercentage) {
		this.finalPercentage = finalPercentage;
	}

	public boolean active() {
		return status == RolloutStatus.ACTIVE;
	}

	/** 0 until the rollout has started, then the percentage of the latest stage whose offset has passed. */
	public int percentageAt(Instant now) {
		if (now.isBefore(startAt)) {
			return 0;
		}

		long elapsedSeconds = Duration.between(startAt, now).getSeconds();
		int percentage = 0;

		for (RolloutStage stage : stages) {
			if (stage.getOffsetMinutes() * 60L > elapsedSeconds) {
				break;
			}

			percentage = stage.getPercentage();
		}

		return percentage;
	}

	/** Index of the stage in effect at {@code now}, or -1 before the rollout has started. */
	public int stageIndexAt(Instant now) {
		if (now.isBefore(startAt)) {
			return -1;
		}

		long elapsedSeconds = Duration.between(startAt, now).getSeconds();
		int index = -1;

		for (int position = 0; position < stages.size(); position++) {
			if (stages.get(position).getOffsetMinutes() * 60L > elapsedSeconds) {
				break;
			}

			index = position;
		}

		return index;
	}

	/** When the next stage begins, or empty once the final stage has been reached. */
	public Optional<Instant> nextStageAt(Instant now) {
		int nextIndex = stageIndexAt(now) + 1;

		if (nextIndex >= stages.size()) {
			return Optional.empty();
		}

		return Optional.of(startAt.plus(Duration.ofMinutes(stages.get(nextIndex).getOffsetMinutes())));
	}

	/** The moment the final (100%) stage begins, i.e. when the rollout is due to complete. */
	public Instant completesAt() {
		return startAt.plus(Duration.ofMinutes(stages.get(stages.size() - 1).getOffsetMinutes()));
	}
}
