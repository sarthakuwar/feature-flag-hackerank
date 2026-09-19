package com.featureflag.flags.targeting;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.featureflag.flags.UserContext;
import com.featureflag.shared.FieldErrors;

/** Context-kind and member-key checks shared by rule, individual-target, clause and segment validation. */
final class TargetingValidation {

	private static final Pattern CONTEXT_KIND_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{0,49}$");

	private TargetingValidation() {
	}

	/** A blank or missing kind means the default "user" kind. */
	static String normalizeKind(String kind) {
		return kind == null || kind.isBlank() ? UserContext.USER_KIND : kind;
	}

	static void validateKind(String kind, String field, FieldErrors errors) {
		if (!CONTEXT_KIND_PATTERN.matcher(kind).matches()) {
			errors.add(field, "Context kind must be lowercase letters, numbers, underscores and hyphens, starting with a letter.");
		}
	}

	/** Trims, drops blanks and duplicates, and enforces the per-request cap and per-key length. */
	static Set<String> cleanKeys(List<String> raw, int maxKeys, String field, FieldErrors errors) {
		Set<String> keys = new LinkedHashSet<>();

		if (raw == null) {
			return keys;
		}

		if (raw.size() > maxKeys) {
			errors.add(field, "At most " + maxKeys + " keys per request.");

			return keys;
		}

		for (String key : raw) {
			String trimmed = key == null ? "" : key.trim();

			if (trimmed.isEmpty()) {
				continue;
			}

			if (trimmed.length() > TargetingLimits.MAX_VALUE_LENGTH) {
				errors.add(field, "Each key must be at most " + TargetingLimits.MAX_VALUE_LENGTH + " characters.");
			} else {
				keys.add(trimmed);
			}
		}

		return keys;
	}
}
