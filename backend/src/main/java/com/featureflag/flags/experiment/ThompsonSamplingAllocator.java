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
 * beta = max(1, impressions - conversions + 1)) (a Beta(1,1) uniform prior). Conversions are
 * counted per event and never require a prior impression, so they can exceed impressions; beta is
 * clamped so the shape stays valid. {@code Random} is injected via constructor so tests can seed
 * it for deterministic assertions; production wiring uses the no-arg constructor.
 */
@Component
public class ThompsonSamplingAllocator implements BanditAllocator {

	private static final int MONTE_CARLO_ROUNDS = 2000;

	/** Marsaglia-Tsang accepts about 95% of proposals, so this bound is only reached by a broken random source. */
	private static final int MAX_GAMMA_ATTEMPTS = 1000;

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
				double alpha = Math.max(1.0, candidate.conversions() + 1.0);
				double beta = Math.max(1.0, (double) candidate.impressions() - candidate.conversions() + 1.0);
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
		double total = x + y;

		if (!(total > 0) || Double.isInfinite(total)) {
			return alpha / (alpha + beta);
		}

		return x / total;
	}

	/**
	 * Marsaglia-Tsang method, valid for shape >= 1; callers clamp alpha and beta to at least 1, so the
	 * shape < 1 boost trick is never needed. The loop is bounded and falls back to the distribution's
	 * mean (equal to the shape) so a degenerate input can never spin a request thread.
	 */
	private double sampleGamma(double shape) {
		double d = shape - 1.0 / 3.0;
		double c = 1.0 / Math.sqrt(9.0 * d);

		for (int attempt = 0; attempt < MAX_GAMMA_ATTEMPTS; attempt++) {
			double x = random.nextGaussian();
			double v = 1.0 + c * x;

			if (v <= 0) {
				continue;
			}

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

		return shape;
	}
}
