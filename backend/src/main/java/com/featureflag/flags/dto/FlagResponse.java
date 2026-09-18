package com.featureflag.flags.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagLifecycle;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.Variation;

public record FlagResponse(String id, String key, String name, String description, FlagType type,
		FlagLifecycle lifecycle, String owner, List<Variation> variations, List<String> dependsOn,
		Map<EnvironmentKey, EnvironmentConfig> environments, Instant createdAt, Instant updatedAt) {

	public static FlagResponse from(Flag flag) {
		return new FlagResponse(flag.getId(), flag.getKey(), flag.getName(), flag.getDescription(), flag.getType(),
				flag.getLifecycle(), flag.getOwner(), flag.getVariations(), flag.getDependsOn(), flag.getEnvironments(),
				flag.getCreatedAt(), flag.getUpdatedAt());
	}
}
