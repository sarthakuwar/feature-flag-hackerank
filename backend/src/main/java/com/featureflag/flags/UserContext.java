package com.featureflag.flags;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who a flag is being evaluated for. {@code userId}/{@code attributes} are the "user" context kind
 * every existing caller already supplies; {@code contexts} carries any additional kinds
 * (organization, ld_application, ld_device...) keyed by kind name, which is what lets a single
 * targeting rule combine conditions across context kinds.
 */
public record UserContext(String userId, Map<String, String> attributes, Map<String, ContextEntry> contexts) {

	public static final String USER_KIND = "user";

	public UserContext {
		attributes = ContextEntry.copyWithoutNulls(attributes);
		contexts = copyContexts(contexts);
	}

	public UserContext(String userId, Map<String, String> attributes) {
		this(userId, attributes, null);
	}

	public static UserContext anonymous(String userId) {
		return new UserContext(userId, Collections.emptyMap(), null);
	}

	/** The user kind is always present (its key may be null for an anonymous caller); other kinds only if supplied. */
	public ContextEntry contextOfKind(String kind) {
		if (kind == null || USER_KIND.equals(kind)) {
			return new ContextEntry(userId, attributes);
		}

		return contexts.get(kind);
	}

	private static Map<String, ContextEntry> copyContexts(Map<String, ContextEntry> source) {
		if (source == null || source.isEmpty()) {
			return Collections.emptyMap();
		}

		Map<String, ContextEntry> copy = new LinkedHashMap<>();
		source.forEach((kind, entry) -> {
			if (kind != null && entry != null && !USER_KIND.equals(kind)) {
				copy.put(kind, entry);
			}
		});

		return Map.copyOf(copy);
	}
}
