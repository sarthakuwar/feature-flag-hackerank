package com.featureflag.flags.targeting.dto;

import java.time.Instant;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.targeting.Segment;

/** List-view shape: counts instead of the (potentially huge) member lists. */
public record SegmentSummaryResponse(String key, EnvironmentKey environment, String name, String description,
		String contextKind, int includedCount, int excludedCount, int ruleCount, String updatedBy, Instant createdAt,
		Instant updatedAt) {

	public static SegmentSummaryResponse from(Segment segment) {
		return new SegmentSummaryResponse(segment.getKey(), segment.getEnvironment(), segment.getName(),
				segment.getDescription(), segment.getContextKind(), segment.getIncluded().size(),
				segment.getExcluded().size(), segment.getRules().size(), segment.getUpdatedBy(), segment.getCreatedAt(),
				segment.getUpdatedAt());
	}
}
