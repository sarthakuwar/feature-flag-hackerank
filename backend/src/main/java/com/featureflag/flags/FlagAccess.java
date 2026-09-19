package com.featureflag.flags;

import org.springframework.stereotype.Component;

import com.featureflag.shared.ApiException;

/**
 * Loads a flag for a service to act on and resolves one of its environments, so every service
 * rejects a missing flag, an archived flag, and a missing environment with the same error.
 */
@Component
public class FlagAccess {

	private final FlagRepository flagRepository;

	public FlagAccess(FlagRepository flagRepository) {
		this.flagRepository = flagRepository;
	}

	public Flag getFlag(String key) {
		return flagRepository.findByKey(key)
				.orElseThrow(() -> new ApiException(404, "FLAG_NOT_FOUND", "No flag exists with key '" + key + "'."));
	}

	/** An archived flag is retired: it can no longer be evaluated or edited. Restore it first. */
	public Flag getEditableFlag(String key) {
		Flag flag = getFlag(key);

		if (flag.getLifecycle() == FlagLifecycle.ARCHIVED) {
			throw new ApiException(409, "FLAG_ARCHIVED",
					"Flag '" + flag.getKey() + "' is archived and cannot be modified. Restore it first.");
		}

		return flag;
	}

	public static EnvironmentConfig requireEnvironment(Flag flag, EnvironmentKey environment) {
		EnvironmentConfig config = flag.getEnvironments().get(environment);

		if (config == null) {
			throw new ApiException(404, "ENVIRONMENT_NOT_FOUND",
					"Flag '" + flag.getKey() + "' has no configuration for environment '" + environment + "'.");
		}

		return config;
	}
}
