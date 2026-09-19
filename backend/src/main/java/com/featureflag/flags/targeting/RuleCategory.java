package com.featureflag.flags.targeting;

/** How the UI groups a rule: purely derived from its clauses, never stored. */
public enum RuleCategory {
	/** Every clause is a segment membership check. */
	SEGMENT,
	/** At least one clause targets ld_application/ld_device or app-version support. */
	MOBILE,
	CUSTOM
}
