package com.featureflag.flags.experiment.dto;

import com.featureflag.flags.BanditAlgorithm;

public record BanditConfigRequest(BanditAlgorithm algorithm, Double epsilon, String changedBy) {
}
