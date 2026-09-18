package com.featureflag.flags;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Flag evaluation is a pure function: (flag definition, environment, user context) -> variation.
 * Order: (1) environment enabled check, (2) the environment's strategies, in priority order.
 * USER_ID/ATTRIBUTE strategies resolve immediately on a match; a PERCENTAGE_ROLLOUT strategy
 * resolves only if the stable-hash bucket falls under its percentage, otherwise evaluation falls
 * through to the next strategy. If nothing in the list resolves it, the environment's
 * offVariationKey is served. No I/O, no side effects, so it stays sub-millisecond.
 */
@Service
public class FlagEvaluationService {

	private static final int BUCKET_COUNT = 100;

	public EvaluationResult evaluate(Flag flag, EnvironmentKey environment, UserContext userContext) {
		EnvironmentConfig config = flag.getEnvironments().get(environment);

		if (config == null || !config.isEnabled()) {
			String offVariationKey = config == null ? fallbackVariationKey(flag) : config.getOffVariationKey();

			return EvaluationResult.of(flag.getKey(), offVariationKey, valueOf(flag, offVariationKey),
					EvaluationReason.FLAG_DISABLED);
		}

		for (Strategy strategy : config.orderedStrategies()) {
			if (strategy.getType() == StrategyType.PERCENTAGE_ROLLOUT) {
				int bucket = bucketFor(flag.getKey(), userContext);

				if (bucket < strategy.getPercentage()) {
					return EvaluationResult.of(flag.getKey(), strategy.getServedVariationKey(),
							valueOf(flag, strategy.getServedVariationKey()), EvaluationReason.ROLLOUT_ENABLED);
				}
			} else if (strategy.getType() == StrategyType.EXPERIMENT_SPLIT) {
				int bucket = bucketFor(flag.getKey(), userContext);
				String variationKey = resolveExperimentVariation(flag, strategy, bucket);

				return EvaluationResult.of(flag.getKey(), variationKey, valueOf(flag, variationKey),
						EvaluationReason.EXPERIMENT_ASSIGNED);
			} else if (strategy.matches(userContext)) {
				return EvaluationResult.strategyMatch(flag.getKey(), strategy.getServedVariationKey(),
						valueOf(flag, strategy.getServedVariationKey()), strategy.getId());
			}
		}

		return EvaluationResult.of(flag.getKey(), config.getOffVariationKey(), valueOf(flag, config.getOffVariationKey()),
				EvaluationReason.FALLTHROUGH);
	}

	/**
	 * MD5 of "{flagKey}:{userId}" mod 100. Deterministic for a given flag/user pair, so the same
	 * user always lands in the same bucket across evaluations, processes, and rollout percentage
	 * changes (raising the percentage only ever adds users, never reshuffles existing ones). All
	 * PERCENTAGE_ROLLOUT strategies on the same flag share this bucket per the spec's explicit
	 * "hash the flag key and user ID" rule, rather than being salted per-strategy.
	 */
	public int bucketFor(String flagKey, UserContext userContext) {
		String bucketingId = userContext == null || userContext.userId() == null ? "anonymous" : userContext.userId();
		String input = flagKey + ":" + bucketingId;

		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

			int unsignedValue = ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16) | ((hash[2] & 0xFF) << 8)
					| (hash[3] & 0xFF);

			return Math.floorMod(unsignedValue, BUCKET_COUNT);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("MD5 is not available on this JVM.", exception);
		}
	}

	/**
	 * Walks {@code flag.getVariations()} in list order (never the weights map, whose iteration
	 * order isn't guaranteed stable across (de)serialization) accumulating each variation's weight,
	 * and returns the first one whose cumulative share exceeds the bucket. Weights are validated to
	 * sum to 100 at write time, so every bucket 0-99 resolves; the last variation is returned as a
	 * defensive fallback only if that invariant were ever violated.
	 */
	private String resolveExperimentVariation(Flag flag, Strategy strategy, int bucket) {
		List<Variation> variations = flag.getVariations();
		int cumulative = 0;

		for (Variation variation : variations) {
			cumulative += strategy.getVariationWeights().getOrDefault(variation.getKey(), 0);

			if (bucket < cumulative) {
				return variation.getKey();
			}
		}

		return variations.isEmpty() ? null : variations.get(variations.size() - 1).getKey();
	}

	private String valueOf(Flag flag, String variationKey) {
		return flag.getVariations().stream()
				.filter(variation -> variation.getKey().equals(variationKey))
				.map(Variation::getValue)
				.findFirst()
				.orElse(null);
	}

	private String fallbackVariationKey(Flag flag) {
		List<Variation> variations = flag.getVariations();

		return Optional.ofNullable(variations.isEmpty() ? null : variations.get(0)).map(Variation::getKey).orElse(null);
	}
}
