package com.featureflag.flags.targeting.dto;

public record MobileApplicationUpdateRequest(String name, String minSupportedVersion, String changedBy) {
}
