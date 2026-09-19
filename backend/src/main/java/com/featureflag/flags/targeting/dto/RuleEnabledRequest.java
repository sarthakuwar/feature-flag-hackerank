package com.featureflag.flags.targeting.dto;

public record RuleEnabledRequest(boolean enabled, String changedBy) {
}
