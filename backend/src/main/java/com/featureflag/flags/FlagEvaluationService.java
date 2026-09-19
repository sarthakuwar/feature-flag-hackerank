package com.featureflag.flags;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.featureflag.flags.targeting.ClauseMatcher;
import com.featureflag.flags.targeting.IndividualTarget;
import com.featureflag.flags.targeting.TargetingLookup;
import com.featureflag.flags.targeting.TargetingRule;

/**
 * Flag evaluation is a pure function: (flag definition, environment, user context) -> variation.
 * Order: (1) environment enabled check, (2) individual targets, (3) targeting rules in list order
 * (disabled rules skipped; all of a rule's clauses must match, and it serves a fixed variation or a
 * percentage rollout), (4) the environment's strategies, in priority order. USER_ID/ATTRIBUTE
 * strategies resolve immediately on a match; a PERCENTAGE_ROLLOUT strategy resolves only if the
 * stable-hash bucket falls under its percentage, otherwise evaluation falls through to the next
 * strategy. If nothing resolves it, the environment's offVariationKey is served. Segments and the
 * mobile application registry are read from an in-memory {@link TargetingLookup}: no I/O, no side
 * effects, so it stays sub-millisecond.
 *
 * <p>A strategy with an ACTIVE progressive rollout resolves in two steps: the strategy's normal
 * audience check first, then the user's stable-hash bucket against the rollout's current percentage
 * (a pure function of the schedule stored on the flag and the injected clock) to choose between the
 * rollout's from and to variations. Reading the clock is the only environmental input. Progressive
 * rollouts currently attach to strategies, not to the targeting rules of step (3).
 */
@Service
public class FlagEvaluationService {

	private static final int BUCKET_COUNT = 100;

	private final ClauseMatcher clauseMatcher;

	private final Clock clock;

	@Autowired
	public FlagEvaluationService(TargetingLookup targetingLookup, Clock clock) {
		this.clauseMatcher = new ClauseMatcher(targetingLookup);
		this.clock = clock;
	}

	/** Real clock, with the given segment/application store. */
	public FlagEvaluationService(TargetingLookup targetingLookup) {
		this(targetingLookup, Clock.systemUTC());
	}

	/** No segments or applications known (every segment clause simply doesn't match), with the given clock. */
	public FlagEvaluationService(Clock clock) {
		this(TargetingLookup.EMPTY, clock);
	}

	/** No segments or applications known, real clock. */
	public FlagEvaluationService() {
		this(TargetingLookup.EMPTY, Clock.systemUTC());
	}

	public EvaluationResult evaluate(Flag flag, EnvironmentKey environment, UserContext userContext) {
		EnvironmentConfig config = flag.getEnvironments().get(environment);

		if (config == null || !config.isEnabled()) {
			String offVariationKey = config == null ? fallbackVariationKey(flag) : config.getOffVariationKey();

			return EvaluationResult.of(flag.getKey(), offVariationKey, valueOf(flag, offVariationKey),
					EvaluationReason.FLAG_DISABLED);
		}

		EvaluationResult targeted = evaluateTargeting(flag, config, environment, userContext);

		if (targeted != null) {
			return targeted;
		}

		for (Strategy strategy : config.orderedStrategies()) {
			if (strategy.getType() == StrategyType.PERCENTAGE_ROLLOUT) {
				int bucket = bucketFor(flag.getKey(), userContext);

				if (bucket < strategy.getPercentage()) {
					Optional<ProgressiveRollout> progressive = config.activeRolloutFor(strategy.getId());

					if (progressive.isPresent()) {
						return progressiveResult(flag, strategy, progressive.get(), bucket);
					}

					return EvaluationResult.of(flag.getKey(), strategy.getServedVariationKey(),
							valueOf(flag, strategy.getServedVariationKey()), EvaluationReason.ROLLOUT_ENABLED);
				}
			} else if (strategy.getType() == StrategyType.EXPERIMENT_SPLIT) {
				int bucket = bucketFor(flag.getKey(), userContext);
				String variationKey = resolveWeightedVariation(flag, strategy.getVariationWeights(), bucket);

				return EvaluationResult.of(flag.getKey(), variationKey, valueOf(flag, variationKey),
						EvaluationReason.EXPERIMENT_ASSIGNED);
			} else if (strategy.matches(userContext)) {
				Optional<ProgressiveRollout> progressive = config.activeRolloutFor(strategy.getId());

				if (progressive.isPresent()) {
					return progressiveResult(flag, strategy, progressive.get(), bucketFor(flag.getKey(), userContext));
				}

				return EvaluationResult.strategyMatch(flag.getKey(), strategy.getServedVariationKey(),
						valueOf(flag, strategy.getServedVariationKey()), strategy.getId());
			}
		}

		return EvaluationResult.of(flag.getKey(), config.getOffVariationKey(), valueOf(flag, config.getOffVariationKey()),
				EvaluationReason.FALLTHROUGH);
	}

	/** Individual targets, then targeting rules; null when neither resolves and evaluation should continue. */
	private EvaluationResult evaluateTargeting(Flag flag, EnvironmentConfig config, EnvironmentKey environment,
			UserContext userContext) {
		for (IndividualTarget target : config.getIndividualTargets()) {
			ContextEntry entry = userContext == null ? null : userContext.contextOfKind(target.getContextKind());

			if (entry != null && entry.key() != null && target.getKeys().contains(entry.key())) {
				return EvaluationResult.individualTarget(flag.getKey(), target.getVariationKey(),
						valueOf(flag, target.getVariationKey()));
			}
		}

		if (userContext == null) {
			return null;
		}

		for (TargetingRule rule : config.getRules()) {
			if (!rule.isEnabled() || !clauseMatcher.allMatch(rule.getClauses(), userContext, environment)) {
				continue;
			}

			String variationKey = rule.servesRollout()
					? resolveWeightedVariation(flag, rule.getRolloutWeights(), bucketFor(flag.getKey(), userContext))
					: rule.getServedVariationKey();

			return EvaluationResult.ruleMatch(flag.getKey(), variationKey, valueOf(flag, variationKey), rule.getId(),
					rule.isIncludeInExperiment() && rule.servesRollout());
		}

		return null;
	}

	/**
	 * Users whose bucket is below the rollout's current percentage get the new variation, everyone
	 * else in the rule's audience keeps the old one. Because the bucket is stable and the percentage
	 * only ever grows, a user who has moved to the new variation never moves back.
	 */
	private EvaluationResult progressiveResult(Flag flag, Strategy strategy, ProgressiveRollout rollout, int bucket) {
		String variationKey = bucket < rollout.percentageAt(clock.instant()) ? rollout.getToVariationKey()
				: rollout.getFromVariationKey();

		return EvaluationResult.progressiveRollout(flag.getKey(), variationKey, valueOf(flag, variationKey), strategy.getId());
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
	private String resolveWeightedVariation(Flag flag, Map<String, Integer> weights, int bucket) {
		List<Variation> variations = flag.getVariations();
		int cumulative = 0;

		for (Variation variation : variations) {
			cumulative += weights.getOrDefault(variation.getKey(), 0);

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
