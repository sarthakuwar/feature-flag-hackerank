package com.featureflag.flags.dto;

import java.util.List;

public record EnvironmentConfigRequest(Boolean enabled, String offVariationKey, List<StrategyRequest> strategies,
		String changedBy) {
}
