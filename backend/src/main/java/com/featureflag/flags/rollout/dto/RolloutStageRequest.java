package com.featureflag.flags.rollout.dto;

public record RolloutStageRequest(Integer percentage, Integer offsetMinutes) {
}
