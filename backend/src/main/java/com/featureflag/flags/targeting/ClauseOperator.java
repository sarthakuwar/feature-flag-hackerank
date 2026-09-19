package com.featureflag.flags.targeting;

/**
 * What a {@link Clause} checks. Attribute operators compare the context's attribute value against
 * any of the clause's values (OR across values); {@link #IN_SEGMENT} and {@link
 * #APP_VERSION_SUPPORTED} are special forms that don't read an attribute. Use {@link Clause#isNegate()}
 * for the inverse of any of them ("is not one of", "is not in segment", "unsupported").
 */
public enum ClauseOperator {
	/** Exact string equality with any of the values ("is one of"). */
	IN,
	STARTS_WITH,
	ENDS_WITH,
	CONTAINS,
	/** Java regular expression, matched anywhere in the attribute value ("find" semantics). */
	MATCHES,
	LESS_THAN,
	LESS_THAN_OR_EQUAL,
	GREATER_THAN,
	GREATER_THAN_OR_EQUAL,
	SEMVER_EQUAL,
	SEMVER_LESS_THAN,
	SEMVER_GREATER_THAN,
	/** Values are segment keys; true if the context is a member of any of them. */
	IN_SEGMENT,
	/** ld_application contexts only: whether the app version is at or above its registered minimum supported version. */
	APP_VERSION_SUPPORTED;

	public boolean isNumeric() {
		return this == LESS_THAN || this == LESS_THAN_OR_EQUAL || this == GREATER_THAN || this == GREATER_THAN_OR_EQUAL;
	}

	public boolean isSemver() {
		return this == SEMVER_EQUAL || this == SEMVER_LESS_THAN || this == SEMVER_GREATER_THAN;
	}
}
