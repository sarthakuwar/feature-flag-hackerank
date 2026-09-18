package com.featureflag.flags.experiment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.stereotype.Component;

import com.featureflag.flags.metrics.dto.VariationMetrics;

/**
 * Thompson sampling over a Beta-Bernoulli posterior per variation, using only {@link Random} - no
 * math/statistics dependency. Each variation's posterior is Beta(alpha = conversions + 1,
 * beta = impressions - conversions + 1) (a Beta(1,1) uniform prior). {@code Random} is injected
 * via constructor so tests can seed it for deterministic assertions; production wiring uses the
 * no-arg constructor.
 */
@Component
public class ThompsonSamplingAllocator implements BanditAllocator {

	private static final int MONTE_CARLO_ROUNDS = 2000;

	private final Random random;

	public ThompsonSamplingAllocator() {
		this(new Random());
	}

	public ThompsonSamplingAllocator(Random random) {
		this.random = random;
	}

	@Override
	public Map<String, Integer> allocate(List<VariationMetrics> metrics, Double epsilon) {
		Map<String, Integer> weights = new LinkedHashMap<>();

		if (metrics.isEmpty()) {
			return weights;
		}

		int variationCount = metrics.size();
		int[] wins = new int[variationCount];

		for (int round = 0; round < MONTE_CARLO_ROUNDS; round++) {
			int winnerIndex = 0;
			double winnerSample = -1;

			for (int index = 0; index < variationCount; index++) {
				VariationMetrics candidate = metrics.get(index);
				double alpha = candidate.conversions() + 1.0;
				double beta = (candidate.impressions() - candidate.conversions()) + 1.0;
				double sample = sampleBeta(alpha, beta);

				if (sample > winnerSample) {
					winnerSample = sample;
					winnerIndex = index;
				}
			}

			wins[winnerIndex]++;
		}

		int running = 0;
		int largestIndex = 0;

		for (int index = 0; index < variationCount; index++) {
			int weight = (int) Math.round(100.0 * wins[index] / MONTE_CARLO_ROUNDS);
			weights.put(metrics.get(index).variationKey(), weight);
			running += weight;

			if (wins[index] > wins[largestIndex]) {
				largestIndex = index;
			}
		}

		String largestKey = metrics.get(largestIndex).variationKey();
		weights.put(largestKey, weights.get(largestKey) + (100 - running));

		return weights;
	}

	private double sampleBeta(double alpha, double beta) {
		double x = sampleGamma(alpha);
		double y = sampleGamma(beta);

		return x / (x + y);
	}

	/**
	 * Marsaglia-Tsang method, valid for shape >= 1 - always true here since alpha = conversions + 1
	 * and beta = impressions - conversions + 1 are both at least 1 (impressions is never less than
	 * conversions), so the shape < 1 boost trick is never needed.
	 */
	private double sampleGamma(double shape) {
		double d = shape - 1.0 / 3.0;
		double c = 1.0 / Math.sqrt(9.0 * d);

		while (true) {
			double x;
			double v;

			do {
				x = random.nextGaussian();
				v = 1.0 + c * x;
			} while (v <= 0);

			v = v * v * v;
			double u = random.nextDouble();
			double xSquared = x * x;

			if (u < 1 - 0.0331 * xSquared * xSquared) {
				return d * v;
			}

			if (Math.log(u) < 0.5 * xSquared + d * (1 - v + Math.log(v))) {
				return d * v;
			}
		}
	}
}
