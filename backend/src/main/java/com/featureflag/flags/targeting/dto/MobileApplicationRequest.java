package com.featureflag.flags.targeting.dto;

public record MobileApplicationRequest(String key, String name, String minSupportedVersion, String changedBy) {
}
