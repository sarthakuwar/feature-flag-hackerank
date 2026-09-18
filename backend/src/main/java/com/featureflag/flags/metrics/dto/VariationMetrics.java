package com.featureflag.flags.metrics.dto;

public record VariationMetrics(String variationKey, long impressions, long conversions, double conversionRate,
		Integer currentWeight) {
}
