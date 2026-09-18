package com.featureflag.flags.dto;

public record VariationCreateRequest(String key, String name, String value, String changedBy) {
}
