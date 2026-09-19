package com.featureflag.flags.rollout.dto;

public record RolloutStageRequest(int percentage, int offsetMinutes) {
}
