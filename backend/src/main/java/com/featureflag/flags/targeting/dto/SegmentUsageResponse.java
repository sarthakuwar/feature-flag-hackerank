package com.featureflag.flags.targeting.dto;

import com.featureflag.flags.FlagLifecycle;

/** One flag rule that references a segment: what blocks deleting it, and what changes when its members change. */
public record SegmentUsageResponse(String flagKey, String flagName, FlagLifecycle lifecycle, String ruleId,
		String ruleDescription) {
}
