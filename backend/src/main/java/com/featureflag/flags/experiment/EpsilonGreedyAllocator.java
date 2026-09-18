package com.featureflag.flags.experiment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.featureflag.flags.metrics.dto.VariationMetrics;

/**
 * Deterministic closed-form epsilon-greedy: the variation with the best observed conversion rate
 * (ties broken by first-in-variation-order) gets the bulk of traffic; the remaining epsilon share
 * is split evenly across every variation (including the best one) to keep exploring. No {@link
 * java.util.Random} involved, so this is trivially deterministic and unit-testable.
 *
 * <p>When every variation is tied on conversion rate - most commonly because none have any data
 * yet - there is no signal to exploit, so traffic is split uniformly instead of arbitrarily
 * favoring whichever variation happens to be first in the list.
 */
@Component
public class EpsilonGreedyAllocator implements BanditAllocator {

	private static final double DEFAULT_EPSILON = 0.1;

	@Override
	public Map<String, Integer> allocate(List<VariationMetrics> metrics, Double epsilon) {
		Map<String, Integer> weights = new LinkedHashMap<>();

		if (metrics.isEmpty()) {
			return weights;
		}

		double eps = epsilon == null ? DEFAULT_EPSILON : epsilon;
		int variationCount = metrics.size();
		VariationMetrics best = metrics.get(0);
		boolean allTied = true;

		for (VariationMetrics candidate : metrics) {
			if (candidate.conversionRate() > best.conversionRate()) {
				best = candidate;
			}

			if (candidate.conversionRate() != metrics.get(0).conversionRate()) {
				allTied = false;
			}
		}

		if (allTied) {
			return uniformSplit(metrics);
		}

		int running = 0;

		for (VariationMetrics candidate : metrics) {
			double raw = candidate.variationKey().equals(best.variationKey())
					? (1 - eps) * 100 + eps * 100 / variationCount
					: eps * 100 / variationCount;
			int rounded = (int) Math.round(raw);
			weights.put(candidate.variationKey(), rounded);
			running += rounded;
		}

		weights.put(best.variationKey(), weights.get(best.variationKey()) + (100 - running));

		return weights;
	}

	private Map<String, Integer> uniformSplit(List<VariationMetrics> metrics) {
		Map<String, Integer> weights = new LinkedHashMap<>();
		int even = 100 / metrics.size();
		int remainder = 100 - (even * metrics.size());

		for (int index = 0; index < metrics.size(); index++) {
			weights.put(metrics.get(index).variationKey(), even + (index < remainder ? 1 : 0));
		}

		return weights;
	}
}
