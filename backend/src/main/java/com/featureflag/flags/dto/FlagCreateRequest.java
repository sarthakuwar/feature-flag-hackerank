package com.featureflag.flags.dto;

import java.util.List;

import com.featureflag.flags.FlagType;

public record FlagCreateRequest(String key, String name, String description, FlagType type, String owner,
		List<VariationRequest> variations, List<String> dependsOn, String changedBy) {
}
