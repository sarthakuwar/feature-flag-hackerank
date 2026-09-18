package com.featureflag.flags.metrics.dto;

import com.featureflag.flags.EnvironmentKey;

public record ConversionRequest(String flagKey, EnvironmentKey environment, String variationKey, String userId,
		String eventType, Double value) {
}
