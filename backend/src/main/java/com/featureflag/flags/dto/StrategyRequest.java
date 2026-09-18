package com.featureflag.flags.dto;

import java.util.List;
import java.util.Map;

import com.featureflag.flags.BanditAlgorithm;
import com.featureflag.flags.StrategyType;

public record StrategyRequest(String id, StrategyType type, List<String> userIds, String attribute, String attributeValue,
		int percentage, String servedVariationKey, Map<String, Integer> variationWeights, BanditAlgorithm banditAlgorithm,
		Double epsilon) {

	public StrategyRequest(String id, StrategyType type, List<String> userIds, String attribute, String attributeValue,
			int percentage, String servedVariationKey) {
		this(id, type, userIds, attribute, attributeValue, percentage, servedVariationKey, Map.of(), BanditAlgorithm.NONE,
				null);
	}
}
