package com.featureflag.flags.dto;

import java.util.List;

public record FlagUpdateRequest(String name, String description, String owner, List<String> dependsOn, String changedBy) {
}
