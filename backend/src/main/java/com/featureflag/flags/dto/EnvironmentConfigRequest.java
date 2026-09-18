package com.featureflag.flags.dto;

import java.util.List;

public record EnvironmentConfigRequest(boolean enabled, String offVariationKey, List<StrategyRequest> strategies,
		String changedBy) {
}
