package com.featureflag.flags.targeting;

import java.util.Set;

/** Hard caps that keep a single flag or segment document (and the in-memory evaluation of it) bounded. */
public final class TargetingLimits {

	/** Per flag environment, matching the documented "up to 5,000 targeting rules". */
	public static final int MAX_RULES_PER_ENVIRONMENT = 5_000;

	/** Per flag environment: clause values (segment keys included) summed across every rule. */
	public static final int MAX_VALUES_PER_ENVIRONMENT = 50_000;

	/** Per flag environment: individually targeted context keys, summed across every variation. */
	public static final int MAX_INDIVIDUAL_KEYS_PER_ENVIRONMENT = 50_000;

	/** Per segment: explicitly included plus explicitly excluded keys. */
	public static final int MAX_SEGMENT_MEMBERS = 50_000;

	public static final int MAX_SEGMENT_RULES = 200;

	public static final int MAX_VALUE_LENGTH = 256;

	public static final int MAX_REGEX_LENGTH = 200;

	public static final String APPLICATION_KIND = "ld_application";

	public static final String DEVICE_KIND = "ld_device";

	private static final Set<String> MOBILE_KINDS = Set.of(APPLICATION_KIND, DEVICE_KIND);

	private TargetingLimits() {
	}

	public static boolean isMobileKind(String contextKind) {
		return contextKind != null && MOBILE_KINDS.contains(contextKind);
	}
}
