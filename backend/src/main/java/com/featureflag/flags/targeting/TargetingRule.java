package com.featureflag.flags.targeting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An ordered, individually disableable rule on a flag environment. All {@link #clauses} must match
 * (AND). On a match the rule serves either one fixed variation ({@link #servedVariationKey}) or a
 * percentage rollout across variations ({@link #rolloutWeights}, keyed by variation key, summing to
 * 100) - exactly one of the two is set. A disabled rule is skipped by evaluation but kept, so it can
 * be switched back on without being rebuilt. List position in {@code EnvironmentConfig.rules} is the
 * rule's priority.
 *
 * <p>{@link #includeInExperiment} decides whether contexts served by this rule count toward the
 * flag's experiment metrics. It defaults to false and only a rollout rule may set it: a rule that
 * serves one fixed variation to a hand-picked audience is not a randomized comparison, and letting
 * its traffic into the numbers would skew every variation's conversion rate and the bandit built on it.
 */
public class TargetingRule {

	private String id;

	private String description;

	private boolean enabled = true;

	private List<Clause> clauses = new ArrayList<>();

	private String servedVariationKey;

	private Map<String, Integer> rolloutWeights = new LinkedHashMap<>();

	private boolean includeInExperiment;

	public TargetingRule() {
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public List<Clause> getClauses() {
		return clauses;
	}

	public void setClauses(List<Clause> clauses) {
		this.clauses = clauses;
	}

	public String getServedVariationKey() {
		return servedVariationKey;
	}

	public void setServedVariationKey(String servedVariationKey) {
		this.servedVariationKey = servedVariationKey;
	}

	public Map<String, Integer> getRolloutWeights() {
		return rolloutWeights;
	}

	public void setRolloutWeights(Map<String, Integer> rolloutWeights) {
		this.rolloutWeights = rolloutWeights;
	}

	public boolean isIncludeInExperiment() {
		return includeInExperiment;
	}

	public void setIncludeInExperiment(boolean includeInExperiment) {
		this.includeInExperiment = includeInExperiment;
	}

	public boolean servesRollout() {
		return rolloutWeights != null && !rolloutWeights.isEmpty();
	}

	/** Deliberately not a bean getter, so it is serialized for API consumers but never persisted. */
	@JsonProperty("category")
	public RuleCategory category() {
		if (clauses.stream().anyMatch(Clause::targetsMobileContext)) {
			return RuleCategory.MOBILE;
		}

		boolean allSegments = !clauses.isEmpty()
				&& clauses.stream().allMatch(clause -> clause.getOperator() == ClauseOperator.IN_SEGMENT);

		return allSegments ? RuleCategory.SEGMENT : RuleCategory.CUSTOM;
	}

	/** Variation keys this rule can serve, for "is this variation still in use" checks. */
	public List<String> referencedVariationKeys() {
		List<String> keys = new ArrayList<>();

		if (servedVariationKey != null) {
			keys.add(servedVariationKey);
		}

		if (rolloutWeights != null) {
			rolloutWeights.forEach((variationKey, weight) -> {
				if (weight != null && weight > 0) {
					keys.add(variationKey);
				}
			});
		}

		return keys;
	}

	public int valueCount() {
		return clauses.stream().mapToInt(clause -> clause.getValues().size()).sum();
	}
}
