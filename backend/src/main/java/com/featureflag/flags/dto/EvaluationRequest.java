package com.featureflag.flags.dto;

import java.util.Map;

/**
 * The user context to evaluate against. Deliberately a POST body rather than query parameters:
 * attributes here can be PII (email, country, plan), and a GET with that in the URL would land
 * in access logs, browser history, and proxies. This endpoint never writes anything, it's simply
 * transport-safe "read via POST", the same pattern used by search APIs that accept a body.
 */
public record EvaluationRequest(String userId, Map<String, String> attributes) {

	public EvaluationRequest {
		attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
	}
}
