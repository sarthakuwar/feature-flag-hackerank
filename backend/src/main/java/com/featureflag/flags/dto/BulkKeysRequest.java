package com.featureflag.flags.dto;

import java.util.List;

public record BulkKeysRequest(List<String> keys, String changedBy) {
}
