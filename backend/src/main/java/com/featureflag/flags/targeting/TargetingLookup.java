package com.featureflag.flags.targeting;

import java.util.Optional;

import com.featureflag.flags.EnvironmentKey;

/**
 * The reference data flag evaluation needs beyond the flag itself: segment definitions and the
 * mobile application registry. Implementations must be answered from memory - evaluation never does
 * I/O - which is why the production implementation is a cache that the write paths keep fresh.
 */
public interface TargetingLookup {

	TargetingLookup EMPTY = new TargetingLookup() {
		@Override
		public Optional<Segment> segment(EnvironmentKey environment, String segmentKey) {
			return Optional.empty();
		}

		@Override
		public Optional<MobileApplication> application(String applicationKey) {
			return Optional.empty();
		}
	};

	Optional<Segment> segment(EnvironmentKey environment, String segmentKey);

	Optional<MobileApplication> application(String applicationKey);
}
