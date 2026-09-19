package com.featureflag.flags.targeting.dto;

public record RuleEnabledRequest(Boolean enabled, String changedBy) {
}
