package com.featureflag.flags;

/**
 * One step of a progressive rollout schedule: from {@link #offsetMinutes} after the rollout's
 * start, {@link #percentage} of the targeting rule's audience is served the rollout variation.
 */
public class RolloutStage {

	private int percentage;

	private int offsetMinutes;

	public RolloutStage() {
	}

	public RolloutStage(int percentage, int offsetMinutes) {
		this.percentage = percentage;
		this.offsetMinutes = offsetMinutes;
	}

	public int getPercentage() {
		return percentage;
	}

	public void setPercentage(int percentage) {
		this.percentage = percentage;
	}

	public int getOffsetMinutes() {
		return offsetMinutes;
	}

	public void setOffsetMinutes(int offsetMinutes) {
		this.offsetMinutes = offsetMinutes;
	}
}
