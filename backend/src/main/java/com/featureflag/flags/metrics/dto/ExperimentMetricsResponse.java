package com.featureflag.flags.metrics.dto;

import java.util.List;

import com.featureflag.flags.BanditAlgorithm;
import com.featureflag.flags.EnvironmentKey;

public record ExperimentMetricsResponse(String flagKey, EnvironmentKey environment, List<VariationMetrics> variations,
		BanditAlgorithm banditAlgorithm, Double epsilon) {
}
