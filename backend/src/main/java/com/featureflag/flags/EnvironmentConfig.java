package com.featureflag.flags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class EnvironmentConfig {

	private boolean enabled;

	private String offVariationKey;

	private List<Strategy> strategies = new ArrayList<>();

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

	public List<Strategy> orderedStrategies() {
		List<Strategy> ordered = new ArrayList<>(strategies);
		ordered.sort(Comparator.comparingInt(Strategy::getPriority));

		return ordered;
	}
}
