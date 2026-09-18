package com.featureflag.flags.experiment.dto;

public record PromoteRequest(String winningVariationKey, String changedBy) {
}
