package com.featureflag.flags;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.featureflag.flags.targeting.ClauseMatcher;
import com.featureflag.flags.targeting.IndividualTarget;
import com.featureflag.flags.targeting.TargetingLookup;
import com.featureflag.flags.targeting.TargetingRule;

/**
 * Flag evaluation is a pure function: (flag definition, environment, user context) -> variation.
 * Order: (1) environment enabled check, (2) prerequisites (every flag in {@code dependsOn} must be
 * active and enabled in this environment, and so must its own prerequisites; otherwise the off
 * variation is served with PREREQUISITE_FAILED), (3) individual targets, (4) targeting rules in list
 * order (disabled rules skipped; all of a rule's clauses must match, and it serves a fixed variation
 * or a percentage rollout), (5) the environment's strategies, in priority order. USER_ID/ATTRIBUTE
 * strategies resolve immediately on a match; a PERCENTAGE_ROLLOUT strategy resolves only if the
 * stable-hash bucket falls under its percentage, otherwise evaluation falls through to the next
 * strategy. If nothing resolves it, the environment's offVariationKey is served. Segments and the
 * mobile application registry are read from an in-memory {@link TargetingLookup} and prerequisite
 * flags from an in-memory {@link FlagLookup}: no I/O, no side effects, so it stays sub-millisecond.
 *
 * <p>A targeting rule (step 3) or strategy (step 4) with an ACTIVE progressive rollout resolves in
 * two steps: its normal audience check first, then the user's stable-hash bucket against the
 * rollout's current percentage (a pure function of the schedule stored on the flag and the injected
 * clock) to choose between the rollout's from and to variations. Reading the clock is the only
 * environmental input. Only a rule that serves one fixed variation can be ramped; a rule that
 * itself serves a percentage rollout has no single variation to move away from.
 */
@Service
public class FlagEvaluationService {

	private static final int BUCKET_COUNT = 100;

	/** Anything deeper than this chain of prerequisites is treated as unmet, so a bad graph can never run away. */
	private static final int MAX_PREREQUISITE_DEPTH = 10;

	private static final String ANONYMOUS_BUCKETING_ID = "anonymous";

	private final ClauseMatcher clauseMatcher;

	private final FlagLookup flagLookup;

	private final Clock clock;

	@Autowired
	public FlagEvaluationService(TargetingLookup targetingLookup, FlagLookup flagLookup, Clock clock) {
		this.clauseMatcher = new ClauseMatcher(targetingLookup);
		this.flagLookup = flagLookup;
		this.clock = clock;
	}

	/** No prerequisite flags are known, so any flag that has prerequisites fails them. */
	public FlagEvaluationService(TargetingLookup targetingLookup, Clock clock) {
		this(targetingLookup, FlagLookup.EMPTY, clock);
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

	/** An evaluation together with the user's bucket for the flag, hashed at most once. */
	public record BucketedEvaluation(EvaluationResult result, int bucket) {
	}

	public EvaluationResult evaluate(Flag flag, EnvironmentKey environment, UserContext userContext) {
		return evaluate(flag, environment, userContext, new LazyBucket(flag.getKey(), userContext));
	}

	/**
	 * Same result as {@link #evaluate}, plus the bucket the impression records. The bucket is hashed
	 * once and shared with the evaluation itself, instead of once more by the caller.
	 */
	public BucketedEvaluation evaluateWithBucket(Flag flag, EnvironmentKey environment, UserContext userContext) {
		LazyBucket bucket = new LazyBucket(flag.getKey(), userContext);
		EvaluationResult result = evaluate(flag, environment, userContext, bucket);

		return new BucketedEvaluation(result, bucket.get());
	}

	private EvaluationResult evaluate(Flag flag, EnvironmentKey environment, UserContext userContext, LazyBucket bucket) {
		EnvironmentConfig config = flag.getEnvironments().get(environment);

		if (config == null || !config.isEnabled()) {
			String offVariationKey = config == null ? fallbackVariationKey(flag) : config.getOffVariationKey();

			return EvaluationResult.flagDisabled(flag.getKey(), offVariationKey, valueOf(flag, offVariationKey));
		}

		String failedPrerequisite = firstFailedPrerequisite(flag, environment);

		if (failedPrerequisite != null) {
			return EvaluationResult.prerequisiteFailed(flag.getKey(), config.getOffVariationKey(),
					valueOf(flag, config.getOffVariationKey()), failedPrerequisite);
		}

		EvaluationResult targeted = evaluateTargeting(flag, config, environment, userContext, bucket);

		if (targeted != null) {
			return targeted;
		}

		for (Strategy strategy : config.orderedStrategies()) {
			if (strategy.getType() == StrategyType.PERCENTAGE_ROLLOUT) {
				int userBucket = bucket.get();

				if (userBucket < strategy.getPercentage()) {
					Optional<ProgressiveRollout> progressive = config.activeRolloutFor(strategy.getId());

					if (progressive.isPresent()) {
						return progressiveResult(flag, strategy, progressive.get(), userBucket);
					}

					return EvaluationResult.rolloutEnabled(flag.getKey(), strategy.getServedVariationKey(),
							valueOf(flag, strategy.getServedVariationKey()));
				}
			} else if (strategy.getType() == StrategyType.EXPERIMENT_SPLIT) {
				String variationKey = resolveWeightedVariation(flag, strategy.getVariationWeights(), bucket.get());

				return EvaluationResult.experimentAssigned(flag.getKey(), variationKey, valueOf(flag, variationKey));
			} else if (strategy.matches(userContext)) {
				Optional<ProgressiveRollout> progressive = config.activeRolloutFor(strategy.getId());

				if (progressive.isPresent()) {
					return progressiveResult(flag, strategy, progressive.get(), bucket.get());
				}

				return EvaluationResult.strategyMatch(flag.getKey(), strategy.getServedVariationKey(),
						valueOf(flag, strategy.getServedVariationKey()), strategy.getId());
			}
		}

		return EvaluationResult.fallthrough(flag.getKey(), config.getOffVariationKey(), valueOf(flag, config.getOffVariationKey()));
	}

	/** The first key in {@code dependsOn} whose flag is not met in this environment, or null when all are. */
	private String firstFailedPrerequisite(Flag flag, EnvironmentKey environment) {
		List<String> prerequisites = flag.getDependsOn();

		if (prerequisites == null || prerequisites.isEmpty()) {
			return null;
		}

		Set<String> path = new HashSet<>();
		path.add(flag.getKey());
		Map<String, Boolean> resolved = new HashMap<>();

		for (String prerequisiteKey : prerequisites) {
			if (!prerequisiteMet(prerequisiteKey, environment, path, resolved)) {
				return prerequisiteKey;
			}
		}

		return null;
	}

	/**
	 * A prerequisite is met when its flag exists, is ACTIVE, is enabled in the evaluated environment,
	 * and all of its own prerequisites are met. Validation only forbids a flag depending on itself, so
	 * longer cycles can exist in stored data: a flag already on the current path, or a chain deeper
	 * than {@link #MAX_PREREQUISITE_DEPTH}, counts as unmet. A cycle is unmet from every entry point,
	 * so remembering results per evaluation is safe.
	 */
	private boolean prerequisiteMet(String key, EnvironmentKey environment, Set<String> path, Map<String, Boolean> resolved) {
		if (key == null || path.contains(key) || path.size() > MAX_PREREQUISITE_DEPTH) {
			return false;
		}

		Boolean known = resolved.get(key);

		if (known != null) {
			return known;
		}

		boolean met = flagLookup.get(key).map(prerequisite -> {
			EnvironmentConfig prerequisiteConfig = prerequisite.getEnvironments().get(environment);

			if (prerequisite.getLifecycle() != FlagLifecycle.ACTIVE || prerequisiteConfig == null
					|| !prerequisiteConfig.isEnabled()) {
				return false;
			}

			path.add(key);
			boolean nestedMet = prerequisite.getDependsOn() == null || prerequisite.getDependsOn().stream()
					.allMatch(nestedKey -> prerequisiteMet(nestedKey, environment, path, resolved));
			path.remove(key);

			return nestedMet;
		}).orElse(false);

		resolved.put(key, met);

		return met;
	}

	/** Individual targets, then targeting rules; null when neither resolves and evaluation should continue. */
	private EvaluationResult evaluateTargeting(Flag flag, EnvironmentConfig config, EnvironmentKey environment,
			UserContext userContext, LazyBucket bucket) {
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

			if (!rule.servesRollout()) {
				Optional<ProgressiveRollout> progressive = config.activeRolloutForRule(rule.getId());

				if (progressive.isPresent()) {
					String rampedKey = rolloutVariation(progressive.get(), bucket.get());

					return EvaluationResult.progressiveRuleRollout(flag.getKey(), rampedKey, valueOf(flag, rampedKey), rule.getId());
				}
			}

			String variationKey = rule.servesRollout()
					? resolveWeightedVariation(flag, rule.getRolloutWeights(), bucket.get())
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
		String variationKey = rolloutVariation(rollout, bucket);

		return EvaluationResult.progressiveRollout(flag.getKey(), variationKey, valueOf(flag, variationKey), strategy.getId());
	}

	/** Shared by strategy and rule rollouts: the new variation for buckets under the current percentage, else the old one. */
	private String rolloutVariation(ProgressiveRollout rollout, int bucket) {
		return bucket < rollout.percentageAt(clock.instant()) ? rollout.getToVariationKey() : rollout.getFromVariationKey();
	}

	/**
	 * MD5 of "{flagKey}:{userId}" mod 100. Deterministic for a given flag/user pair, so the same
	 * user always lands in the same bucket across evaluations, processes, and rollout percentage
	 * changes (raising the percentage only ever adds users, never reshuffles existing ones). All
	 * PERCENTAGE_ROLLOUT strategies on the same flag share this bucket per the spec's explicit
	 * "hash the flag key and user ID" rule, rather than being salted per-strategy.
	 */
	public int bucketFor(String flagKey, UserContext userContext) {
		String input = flagKey + ":" + bucketingIdOf(userContext);

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
	 * The user id when there is one. A caller without a user id (an organization or device context
	 * only) is bucketed by the first non-blank context key, taking kinds in alphabetical order because
	 * the contexts map has no stable iteration order; only a caller with no key at all shares the
	 * "anonymous" bucket.
	 */
	private String bucketingIdOf(UserContext userContext) {
		if (userContext == null) {
			return ANONYMOUS_BUCKETING_ID;
		}

		if (userContext.userId() != null && !userContext.userId().isBlank()) {
			return userContext.userId();
		}

		return userContext.contexts().entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.map(entry -> entry.getValue().key())
				.filter(key -> key != null && !key.isBlank())
				.findFirst()
				.orElse(ANONYMOUS_BUCKETING_ID);
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

	/** Defers the MD5 until an evaluation step needs the bucket, then reuses it for the rest of that evaluation. */
	private final class LazyBucket {

		private final String flagKey;

		private final UserContext userContext;

		private int value = -1;

		private LazyBucket(String flagKey, UserContext userContext) {
			this.flagKey = flagKey;
			this.userContext = userContext;
		}

		private int get() {
			if (value < 0) {
				value = bucketFor(flagKey, userContext);
			}

			return value;
		}
	}
}
