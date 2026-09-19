package com.featureflag.flags;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One context of a given kind (a user, an organization, a mobile application, a device...) in an
 * evaluation. {@code key} is the context's unique identifier within its kind; targeting clauses can
 * reference it through the reserved attribute name {@code "key"}.
 */
public record ContextEntry(String key, Map<String, String> attributes) {

	public static final String KEY_ATTRIBUTE = "key";

	public ContextEntry {
		attributes = copyWithoutNulls(attributes);
	}

	public String attribute(String name) {
		if (KEY_ATTRIBUTE.equals(name)) {
			return key;
		}

		return attributes.get(name);
	}

	static Map<String, String> copyWithoutNulls(Map<String, String> source) {
		if (source == null || source.isEmpty()) {
			return Map.of();
		}

		Map<String, String> copy = new LinkedHashMap<>();
		source.forEach((name, value) -> {
			if (name != null && value != null) {
				copy.put(name, value);
			}
		});

		return Map.copyOf(copy);
	}
}
