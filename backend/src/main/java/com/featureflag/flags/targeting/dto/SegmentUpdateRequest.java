package com.featureflag.flags.targeting.dto;

import java.util.List;

/** Members are changed through the bulk members endpoint; a null rules list leaves the existing rules untouched. */
public record SegmentUpdateRequest(String name, String description, List<SegmentRuleRequest> rules, String changedBy) {
}
