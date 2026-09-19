package com.featureflag.flags.experiment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.featureflag.flags.BanditAlgorithm;
import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagAccess;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagLifecycle;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagStreamService;
import com.featureflag.flags.Strategy;
import com.featureflag.flags.StrategyType;
import com.featureflag.flags.Variation;
import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.metrics.MetricsService;
import com.featureflag.flags.metrics.dto.ExperimentMetricsResponse;
import com.featureflag.flags.metrics.dto.VariationMetrics;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.FieldErrors;
import com.featureflag.shared.Validation;

/**
 * The allocation control plane for an experiment: manual variation weights ("custom percentage"),
 * bandit configuration (epsilon-greedy / Thompson sampling), reallocation, and promoting a winner
 * to 100%. Always mutates the flag fetched fresh from {@link FlagRepository} (never a cached
 * instance) before saving and refreshing {@link FlagCacheService}, the same pattern {@link
 * com.featureflag.flags.FlagService} already uses.
 */
@Service
public class ExperimentService {

	private static final Logger LOG = LoggerFactory.getLogger(ExperimentService.class);

	private static final String BANDIT_CHANGED_BY = "system:bandit";

	private static final int MAX_CONFLICT_RETRIES = 3;

	private final FlagRepository flagRepository;

	private final FlagCacheService flagCacheService;

	private final FlagStreamService flagStreamService;

	private final AuditService auditService;

	private final MetricsService metricsService;

	private final EpsilonGreedyAllocator epsilonGreedyAllocator;

	private final ThompsonSamplingAllocator thompsonSamplingAllocator;

	private final FlagAccess flagAccess;

	public ExperimentService(FlagRepository flagRepository, FlagCacheService flagCacheService,
			FlagStreamService flagStreamService, AuditService auditService, MetricsService metricsService,
			EpsilonGreedyAllocator epsilonGreedyAllocator, ThompsonSamplingAllocator thompsonSamplingAllocator,
			FlagAccess flagAccess) {
		this.flagRepository = flagRepository;
		this.flagCacheService = flagCacheService;
		this.flagStreamService = flagStreamService;
		this.auditService = auditService;
		this.metricsService = metricsService;
		this.epsilonGreedyAllocator = epsilonGreedyAllocator;
		this.thompsonSamplingAllocator = thompsonSamplingAllocator;
		this.flagAccess = flagAccess;
	}

	public Flag setVariationWeights(String key, EnvironmentKey environment, Map<String, Integer> weights, String changedBy) {
		Flag flag = flagAccess.getEditableFlag(key);
		EnvironmentConfig config = FlagAccess.requireEnvironment(flag, environment);

		FieldErrors errors = new FieldErrors();
		Validation.requireText(changedBy, "changedBy", errors);
		Set<String> variationKeys = flag.getVariations().stream().map(Variation::getKey).collect(Collectors.toSet());
		validateWeights(weights, variationKeys, errors);
		errors.throwIfAny();

		requireNoActiveProgressiveRollout(config, key, environment);

		Strategy strategy = findOrCreateExperimentStrategy(config);
		strategy.setVariationWeights(new LinkedHashMap<>(weights));

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.UPDATE, changedBy,
				"Set experiment weights for " + environment.name().toLowerCase() + ": " + describeWeights(weights) + ".");
		flagStreamService.publishFlagChanged(environment, saved.getKey(), AuditAction.UPDATE.name());

		return saved;
	}

	public Flag configureBandit(String key, EnvironmentKey environment, BanditAlgorithm algorithm, Double epsilon,
			String changedBy) {
		Flag flag = flagAccess.getEditableFlag(key);
		EnvironmentConfig config = FlagAccess.requireEnvironment(flag, environment);

		FieldErrors errors = new FieldErrors();
		Validation.requireText(changedBy, "changedBy", errors);

		Strategy strategy = findExperimentStrategy(config);

		if (strategy == null) {
			errors.add("environment", "Configure variation weights before enabling a bandit.");
		}

		BanditAlgorithm resolvedAlgorithm = algorithm == null ? BanditAlgorithm.NONE : algorithm;

		if (resolvedAlgorithm == BanditAlgorithm.EPSILON_GREEDY) {
			if (epsilon == null || epsilon <= 0 || epsilon > 1) {
				errors.add("epsilon",
						"Epsilon is required and must be between 0 (exclusive) and 1 (inclusive) for EPSILON_GREEDY.");
			}
		} else if (epsilon != null) {
			errors.add("epsilon", "Epsilon only applies to the EPSILON_GREEDY algorithm.");
		}

		errors.throwIfAny();

		strategy.setBanditAlgorithm(resolvedAlgorithm);
		strategy.setEpsilon(resolvedAlgorithm == BanditAlgorithm.EPSILON_GREEDY ? epsilon : null);

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.UPDATE, changedBy,
				"Configured " + environment.name().toLowerCase() + " bandit: " + resolvedAlgorithm.name() + ".");
		flagStreamService.publishFlagChanged(environment, saved.getKey(), AuditAction.UPDATE.name());

		return saved;
	}

	public Flag reallocateNow(String key, EnvironmentKey environment, String changedBy) {
		Flag flag = flagAccess.getEditableFlag(key);
		EnvironmentConfig config = FlagAccess.requireEnvironment(flag, environment);
		Strategy strategy = findExperimentStrategy(config);

		if (strategy == null || strategy.getBanditAlgorithm() == BanditAlgorithm.NONE) {
			throw new ApiException(409, "BANDIT_NOT_CONFIGURED",
					"Flag '" + key + "' has no bandit configured for environment '" + environment + "'.");
		}

		Map<String, Integer> newWeights = computeAllocation(key, environment, strategy);
		strategy.setVariationWeights(newWeights);

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.REALLOCATE, changedBy,
				"Reallocated " + environment.name().toLowerCase() + " traffic via " + strategy.getBanditAlgorithm().name() + ": "
						+ describeWeights(newWeights) + ".");
		flagStreamService.publishFlagChanged(environment, saved.getKey(), AuditAction.REALLOCATE.name());

		return saved;
	}

	/**
	 * Called synchronously right after every conversion write ({@link com.featureflag.flags.metrics.ConversionService}
	 * orchestrates this). A silent no-op whenever no bandit is configured, so recording a conversion
	 * on a flag without a bandit costs nothing beyond the conversion insert. A person may edit the
	 * same flag at the same moment, so a write conflict reloads the flag and recomputes, up to
	 * {@value #MAX_CONFLICT_RETRIES} retries, then skips: the next conversion reallocates again.
	 */
	public void reallocateIfBanditEnabled(String key, EnvironmentKey environment) {
		for (int attempt = 0; attempt <= MAX_CONFLICT_RETRIES; attempt++) {
			try {
				reallocateOnce(key, environment);

				return;
			} catch (OptimisticLockingFailureException exception) {
				// Someone else saved the flag first; the next attempt reloads it and recomputes.
			}
		}

		LOG.warn("Skipped bandit reallocation of flag '{}' in {} after {} conflicting writes.", key, environment,
				MAX_CONFLICT_RETRIES + 1);
	}

	private void reallocateOnce(String key, EnvironmentKey environment) {
		Flag flag = flagRepository.findByKey(key).orElse(null);

		if (flag == null || flag.getLifecycle() != FlagLifecycle.ACTIVE) {
			return;
		}

		EnvironmentConfig config = flag.getEnvironments().get(environment);

		if (config == null) {
			return;
		}

		Strategy strategy = findExperimentStrategy(config);

		if (strategy == null || strategy.getBanditAlgorithm() == BanditAlgorithm.NONE) {
			return;
		}

		Map<String, Integer> newWeights = computeAllocation(key, environment, strategy);
		strategy.setVariationWeights(newWeights);

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.REALLOCATE, BANDIT_CHANGED_BY,
				"Auto-reallocated " + environment.name().toLowerCase() + " traffic via " + strategy.getBanditAlgorithm().name()
						+ " after a conversion: " + describeWeights(newWeights) + ".");
		flagStreamService.publishFlagChanged(environment, saved.getKey(), AuditAction.REALLOCATE.name());
	}

	public Flag promoteToFullRollout(String key, EnvironmentKey environment, String winningVariationKey, String changedBy) {
		Flag flag = flagAccess.getEditableFlag(key);
		EnvironmentConfig config = FlagAccess.requireEnvironment(flag, environment);

		FieldErrors errors = new FieldErrors();
		Validation.requireText(changedBy, "changedBy", errors);

		if (winningVariationKey == null || !flag.hasVariation(winningVariationKey)) {
			errors.add("winningVariationKey", "'" + winningVariationKey + "' does not match a variation on this flag.");
		}

		errors.throwIfAny();

		requireNoActiveProgressiveRollout(config, key, environment);

		Map<String, Integer> fullWeights = new LinkedHashMap<>();

		for (Variation variation : flag.getVariations()) {
			fullWeights.put(variation.getKey(), variation.getKey().equals(winningVariationKey) ? 100 : 0);
		}

		Strategy strategy = findOrCreateExperimentStrategy(config);
		strategy.setVariationWeights(fullWeights);
		strategy.setBanditAlgorithm(BanditAlgorithm.NONE);
		strategy.setEpsilon(null);
		config.setOffVariationKey(winningVariationKey);

		Flag saved = flagRepository.save(flag);
		flagCacheService.put(saved);
		auditService.record(saved.getKey(), AuditAction.PROMOTE, changedBy,
				"Promoted '" + winningVariationKey + "' to 100% of " + environment.name().toLowerCase() + " traffic.");
		flagStreamService.publishFlagChanged(environment, saved.getKey(), AuditAction.PROMOTE.name());

		return saved;
	}

	/** A flag environment runs a progressive rollout or an experiment, never both at once. */
	private void requireNoActiveProgressiveRollout(EnvironmentConfig config, String key, EnvironmentKey environment) {
		if (config.activeProgressiveRollout().isPresent()) {
			throw new ApiException(409, "ROLLOUT_IN_PROGRESS", "Flag '" + key + "' has an active progressive rollout in '"
					+ environment + "'; stop it before starting an experiment.");
		}
	}

	private Map<String, Integer> computeAllocation(String flagKey, EnvironmentKey environment, Strategy strategy) {
		ExperimentMetricsResponse metrics = metricsService.metrics(flagKey, environment, null);
		List<VariationMetrics> variationMetrics = metrics.variations();
		BanditAllocator allocator = strategy.getBanditAlgorithm() == BanditAlgorithm.EPSILON_GREEDY ? epsilonGreedyAllocator
				: thompsonSamplingAllocator;

		return allocator.allocate(variationMetrics, strategy.getEpsilon());
	}

	private Strategy findExperimentStrategy(EnvironmentConfig config) {
		return config.getStrategies().stream().filter(strategy -> strategy.getType() == StrategyType.EXPERIMENT_SPLIT)
				.findFirst().orElse(null);
	}

	private Strategy findOrCreateExperimentStrategy(EnvironmentConfig config) {
		Strategy existing = findExperimentStrategy(config);

		if (existing != null) {
			return existing;
		}

		Strategy strategy = new Strategy();
		strategy.setId(UUID.randomUUID().toString());
		strategy.setPriority(config.getStrategies().size());
		strategy.setType(StrategyType.EXPERIMENT_SPLIT);
		strategy.setVariationWeights(new LinkedHashMap<>());
		strategy.setBanditAlgorithm(BanditAlgorithm.NONE);
		config.getStrategies().add(strategy);

		return strategy;
	}

	private void validateWeights(Map<String, Integer> weights, Set<String> variationKeys, FieldErrors errors) {
		if (weights == null || weights.isEmpty()) {
			errors.add("weights", "Variation weights are required.");

			return;
		}

		if (!weights.keySet().equals(variationKeys)) {
			errors.add("weights", "Weights must cover exactly this flag's variations.");
		}

		int sum = weights.values().stream().mapToInt(weight -> weight == null ? 0 : weight).sum();

		if (sum != 100) {
			errors.add("weights", "Variation weights must sum to 100 (found " + sum + ").");
		}
	}

	private String describeWeights(Map<String, Integer> weights) {
		return weights.entrySet().stream().map(entry -> entry.getKey() + " " + entry.getValue() + "%")
				.collect(Collectors.joining(", "));
	}
}
