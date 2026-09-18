package com.featureflag.flags.dto;

public record VariationUpdateRequest(String name, String value, String changedBy) {
}
