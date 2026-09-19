package com.featureflag.flags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.featureflag.flags.targeting.IndividualTarget;
import com.featureflag.flags.targeting.TargetingRule;

/**
 * Evaluation precedence within an enabled environment: {@link #individualTargets}, then {@link
 * #rules} in list order (skipping disabled ones), then the legacy {@link #strategies} (rollouts and
 * experiments), then {@link #offVariationKey}. Environments written before targeting rules existed
 * simply have empty rule/target lists and evaluate exactly as they always did.
 */
public class EnvironmentConfig {

	private boolean enabled;

	private String offVariationKey;

	private List<Strategy> strategies = new ArrayList<>();

	private List<IndividualTarget> individualTargets = new ArrayList<>();

	private List<TargetingRule> rules = new ArrayList<>();

	/** Every progressive rollout ever run in this environment; at most one is ACTIVE at a time. */
	private List<ProgressiveRollout> progressiveRollouts = new ArrayList<>();

	public EnvironmentConfig() {
	}

	public EnvironmentConfig(boolean enabled, String offVariationKey, List<Strategy> strategies) {
		this.enabled = enabled;
		this.offVariationKey = offVariationKey;
		this.strategies = strategies;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getOffVariationKey() {
		return offVariationKey;
	}

	public void setOffVariationKey(String offVariationKey) {
		this.offVariationKey = offVariationKey;
	}

	public List<Strategy> getStrategies() {
		return strategies;
	}

	public void setStrategies(List<Strategy> strategies) {
		this.strategies = strategies;
	}

	public List<IndividualTarget> getIndividualTargets() {
		return individualTargets;
	}

	public void setIndividualTargets(List<IndividualTarget> individualTargets) {
		this.individualTargets = individualTargets;
	}

	public List<TargetingRule> getRules() {
		return rules;
	}

	public void setRules(List<TargetingRule> rules) {
		this.rules = rules;
	}

	public List<ProgressiveRollout> getProgressiveRollouts() {
		return progressiveRollouts;
	}

	public void setProgressiveRollouts(List<ProgressiveRollout> progressiveRollouts) {
		this.progressiveRollouts = progressiveRollouts;
	}

	public Optional<ProgressiveRollout> activeProgressiveRollout() {
		return progressiveRollouts.stream().filter(ProgressiveRollout::active).findFirst();
	}

	public Optional<ProgressiveRollout> activeRolloutFor(String strategyId) {
		return progressiveRollouts.stream()
				.filter(rollout -> rollout.active() && rollout.getStrategyId().equals(strategyId))
				.findFirst();
	}

	public boolean hasExperiment() {
		return strategies.stream().anyMatch(strategy -> strategy.getType() == StrategyType.EXPERIMENT_SPLIT);
	}

	public List<Strategy> orderedStrategies() {
		List<Strategy> ordered = new ArrayList<>(strategies);
		ordered.sort(Comparator.comparingInt(Strategy::getPriority));

		return ordered;
	}
}
