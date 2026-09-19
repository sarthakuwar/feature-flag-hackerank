package com.featureflag.flags;

/**
 * Announces a change that is not scoped to one environment (creating a flag, editing its details or
 * variations, archiving it) to the stream subscribers of every environment.
 */
final class FlagChangeBroadcast {

	private FlagChangeBroadcast() {
	}

	static void toAllEnvironments(FlagStreamService flagStreamService, String flagKey, String action) {
		for (EnvironmentKey environment : EnvironmentKey.values()) {
			flagStreamService.publishFlagChanged(environment, flagKey, action);
		}
	}
}
