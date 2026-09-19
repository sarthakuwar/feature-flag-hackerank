package com.featureflag.flags.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.featureflag.shared.FieldErrors;

/**
 * The user context to evaluate against. Deliberately a POST body rather than query parameters:
 * attributes here can be PII (email, country, plan), and a GET with that in the URL would land
 * in access logs, browser history, and proxies. This endpoint never writes anything, it's simply
 * transport-safe "read via POST", the same pattern used by search APIs that accept a body.
 *
 * <p>{@code userId}/{@code attributes} are the "user" context. {@code contexts} optionally adds more
 * kinds keyed by kind name ({@code "organization"}, {@code "ld_application"}, {@code "ld_device"}...),
 * each a key plus attributes, so targeting rules can combine conditions across kinds.
 *
 * <p>{@link #validate()} bounds every collection and string an evaluation endpoint accepts, so a
 * single request cannot make evaluation or the impression write arbitrarily expensive.
 */
public record EvaluationRequest(String userId, Map<String, String> attributes, Map<String, ContextRequest> contexts) {

	public static final int MAX_KEY_LENGTH = 256;

	public static final int MAX_ATTRIBUTES_PER_CONTEXT = 100;

	public static final int MAX_ATTRIBUTE_NAME_LENGTH = 128;

	public static final int MAX_ATTRIBUTE_VALUE_LENGTH = 1024;

	public static final int MAX_CONTEXTS = 20;

	public record ContextRequest(String key, Map<String, String> attributes) {
	}

	public EvaluationRequest {
		attributes = withoutNulls(attributes);
		contexts = withoutNulls(contexts);
	}

	public EvaluationRequest(String userId, Map<String, String> attributes) {
		this(userId, attributes, null);
	}

	/** Throws 400 VALIDATION_ERROR with a field error for every limit that is exceeded. */
	public void validate() {
		FieldErrors errors = new FieldErrors();

		requireMaxLength(userId, MAX_KEY_LENGTH, "userId", "userId", errors);
		validateAttributes(attributes, "attributes", errors);

		if (contexts.size() > MAX_CONTEXTS) {
			errors.add("contexts", "At most " + MAX_CONTEXTS + " contexts are allowed.");
		} else {
			contexts.forEach((kind, context) -> {
				if (kind.length() > MAX_ATTRIBUTE_NAME_LENGTH) {
					errors.add("contexts", "A context kind must be at most " + MAX_ATTRIBUTE_NAME_LENGTH + " characters.");
					return;
				}

				String field = "contexts." + kind;

				requireMaxLength(context.key(), MAX_KEY_LENGTH, field + ".key", "A context key", errors);
				validateAttributes(withoutNulls(context.attributes()), field + ".attributes", errors);
			});
		}

		errors.throwIfAny();
	}

	private static void validateAttributes(Map<String, String> attributes, String field, FieldErrors errors) {
		if (attributes.size() > MAX_ATTRIBUTES_PER_CONTEXT) {
			errors.add(field, "At most " + MAX_ATTRIBUTES_PER_CONTEXT + " attributes are allowed per context.");
			return;
		}

		attributes.forEach((name, value) -> {
			if (name.length() > MAX_ATTRIBUTE_NAME_LENGTH) {
				errors.add(field, "An attribute name must be at most " + MAX_ATTRIBUTE_NAME_LENGTH + " characters.");
			} else if (value.length() > MAX_ATTRIBUTE_VALUE_LENGTH) {
				errors.add(field, "The value of attribute '" + name + "' must be at most " + MAX_ATTRIBUTE_VALUE_LENGTH
						+ " characters.");
			}
		});
	}

	private static void requireMaxLength(String value, int max, String field, String label, FieldErrors errors) {
		if (value != null && value.length() > max) {
			errors.add(field, label + " must be at most " + max + " characters.");
		}
	}

	private static <V> Map<String, V> withoutNulls(Map<String, V> source) {
		if (source == null || source.isEmpty()) {
			return Map.of();
		}

		Map<String, V> copy = new LinkedHashMap<>();
		source.forEach((name, value) -> {
			if (name != null && value != null) {
				copy.put(name, value);
			}
		});

		return Map.copyOf(copy);
	}
}
