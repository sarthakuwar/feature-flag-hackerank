package com.featureflag.flags.targeting.dto;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.targeting.Segment;
import com.featureflag.flags.targeting.SegmentRule;

public record SegmentResponse(String key, EnvironmentKey environment, String name, String description,
		String contextKind, Set<String> included, Set<String> excluded, List<SegmentRule> rules, String updatedBy,
		Instant createdAt, Instant updatedAt) {

	public static SegmentResponse from(Segment segment) {
		return new SegmentResponse(segment.getKey(), segment.getEnvironment(), segment.getName(),
				segment.getDescription(), segment.getContextKind(), segment.getIncluded(), segment.getExcluded(),
				segment.getRules(), segment.getUpdatedBy(), segment.getCreatedAt(), segment.getUpdatedAt());
	}
}
