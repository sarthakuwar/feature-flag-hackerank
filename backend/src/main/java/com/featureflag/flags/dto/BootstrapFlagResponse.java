package com.featureflag.flags.dto;

import java.util.List;

import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.Variation;

/**
 * What a real SDK fetches once on startup to build its local cache: the flag's shape (not a
 * pre-evaluated result) scoped to a single environment. Only that one environment's targeting
 * rules/rollout are included, not the other two - no reason for a client bootstrapping for
 * PRODUCTION to also receive DEVELOPMENT's targeting rules.
 */
public record BootstrapFlagResponse(String key, String name, FlagType type, List<Variation> variations,
		EnvironmentConfig environment) {

	public static BootstrapFlagResponse from(Flag flag, EnvironmentConfig environmentConfig) {
		return new BootstrapFlagResponse(flag.getKey(), flag.getName(), flag.getType(), flag.getVariations(), environmentConfig);
	}
}
