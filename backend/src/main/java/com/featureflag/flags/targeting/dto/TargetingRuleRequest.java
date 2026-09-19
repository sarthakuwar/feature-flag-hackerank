package com.featureflag.flags.targeting.dto;

import java.util.List;
import java.util.Map;

/**
 * Serve exactly one of servedVariationKey (a fixed variation) or rolloutWeights (variationKey -> 0-100
 * share, summing to 100). enabled defaults to true. position (0-based) only applies when creating a
 * rule and defaults to the end of the list.
 *
 * <p>includeInExperiment makes contexts served by this rule count toward the flag's experiment
 * metrics; it is only valid together with rolloutWeights. It defaults to false on create, and on
 * update a null keeps the rule's current choice (reset to false if the rule now serves a fixed
 * variation, since only a rollout can be part of an experiment).
 */
public record TargetingRuleRequest(String description, Boolean enabled, List<ClauseRequest> clauses,
		String servedVariationKey, Map<String, Integer> rolloutWeights, Integer position, String changedBy,
		Boolean includeInExperiment) {

	public TargetingRuleRequest(String description, Boolean enabled, List<ClauseRequest> clauses,
			String servedVariationKey, Map<String, Integer> rolloutWeights, Integer position, String changedBy) {
		this(description, enabled, clauses, servedVariationKey, rolloutWeights, position, changedBy, null);
	}
}
