package com.featureflag.flags.rollout.dto;

/** {@code variationKey} is the variation the targeting rule serves from the moment the rollout stops. */
public record StopProgressiveRolloutRequest(String variationKey, String changedBy) {
}
