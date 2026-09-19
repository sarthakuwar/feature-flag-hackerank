package com.featureflag.flags.targeting.dto;

import java.util.List;

import com.featureflag.flags.targeting.BulkOperation;

/**
 * Add or remove many context keys for one variation in one call. ADD also takes a key away from any
 * other variation it was individually targeted to (a context is served one variation), REMOVE only
 * removes it from the named one. contextKind defaults to "user".
 */
public record BulkTargetRequest(String contextKind, String variationKey, BulkOperation operation, List<String> keys,
		String changedBy) {
}
