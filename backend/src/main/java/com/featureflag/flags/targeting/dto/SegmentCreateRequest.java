package com.featureflag.flags.targeting.dto;

import java.util.List;

public record SegmentCreateRequest(String key, String name, String description, String contextKind,
		List<String> included, List<String> excluded, List<SegmentRuleRequest> rules, String changedBy) {
}
