package com.featureflag.flags.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The user context to evaluate against. Deliberately a POST body rather than query parameters:
 * attributes here can be PII (email, country, plan), and a GET with that in the URL would land
 * in access logs, browser history, and proxies. This endpoint never writes anything, it's simply
 * transport-safe "read via POST", the same pattern used by search APIs that accept a body.
 *
 * <p>{@code userId}/{@code attributes} are the "user" context. {@code contexts} optionally adds more
 * kinds keyed by kind name ({@code "organization"}, {@code "ld_application"}, {@code "ld_device"}...),
 * each a key plus attributes, so targeting rules can combine conditions across kinds.
 */
public record EvaluationRequest(String userId, Map<String, String> attributes, Map<String, ContextRequest> contexts) {

	public record ContextRequest(String key, Map<String, String> attributes) {
	}

	public EvaluationRequest {
		attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
		contexts = withoutNulls(contexts);
	}

	public EvaluationRequest(String userId, Map<String, String> attributes) {
		this(userId, attributes, null);
	}

	private static Map<String, ContextRequest> withoutNulls(Map<String, ContextRequest> source) {
		if (source == null || source.isEmpty()) {
			return Map.of();
		}

		Map<String, ContextRequest> copy = new LinkedHashMap<>();
		source.forEach((kind, context) -> {
			if (kind != null && context != null) {
				copy.put(kind, context);
			}
		});

		return Map.copyOf(copy);
	}
}
