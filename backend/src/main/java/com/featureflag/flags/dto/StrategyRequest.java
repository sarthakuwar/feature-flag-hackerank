package com.featureflag.flags.dto;

import java.util.List;

import com.featureflag.flags.StrategyType;

public record StrategyRequest(String id, StrategyType type, List<String> userIds, String attribute, String attributeValue,
		int percentage, String servedVariationKey) {
}
