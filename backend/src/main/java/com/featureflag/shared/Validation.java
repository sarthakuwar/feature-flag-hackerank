package com.featureflag.shared;

import java.util.Set;
import java.util.regex.Pattern;

/** Field checks that several services apply the same way, each adding its problem to a {@link FieldErrors}. */
public final class Validation {

	private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");

	private Validation() {
	}

	public static void requireText(String value, String field, FieldErrors errors) {
		if (value == null || value.isBlank()) {
			errors.add(field, "This field is required.");
		}
	}

	public static boolean isWellFormedKey(String key) {
		return key != null && KEY_PATTERN.matcher(key).matches();
	}

	/** A flag or segment key: required, lowercase letters, numbers and hyphens, starting with a letter. */
	public static void validateKey(String key, FieldErrors errors) {
		if (key == null || key.isBlank()) {
			errors.add("key", "Key is required.");
		} else if (!KEY_PATTERN.matcher(key).matches()) {
			errors.add("key", "Key must be lowercase letters, numbers, and hyphens, starting with a letter.");
		}
	}

	public static void validateVariationReference(String variationKey, Set<String> variationKeys, String field,
			FieldErrors errors) {
		if (variationKey == null || variationKey.isBlank()) {
			errors.add(field, "A variation key is required.");
		} else if (!variationKeys.contains(variationKey)) {
			errors.add(field, "'" + variationKey + "' does not match a variation on this flag.");
		}
	}
}
