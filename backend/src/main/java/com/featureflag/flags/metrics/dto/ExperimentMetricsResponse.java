package com.featureflag.flags.metrics.dto;

import java.util.List;

import com.featureflag.flags.BanditAlgorithm;
import com.featureflag.flags.EnvironmentKey;

/**
 * {@code excludedImpressions} counts evaluations that were left out of {@code variations} because the
 * user was individually targeted or matched a rule that isn't included in the experiment - shown so a
 * reader can see why the totals are smaller than raw traffic.
 */
public record ExperimentMetricsResponse(String flagKey, EnvironmentKey environment, List<VariationMetrics> variations,
		BanditAlgorithm banditAlgorithm, Double epsilon, long excludedImpressions) {
}
