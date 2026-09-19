package com.featureflag.flags.targeting.dto;

import java.util.List;

/** The complete new order: must contain every existing rule id exactly once. */
public record RuleOrderRequest(List<String> ruleIds, String changedBy) {
}
