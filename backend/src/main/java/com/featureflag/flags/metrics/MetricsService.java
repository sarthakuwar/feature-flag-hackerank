package com.featureflag.flags.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.featureflag.flags.BanditAlgorithm;
import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.EvaluationReason;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagLifecycle;
import com.featureflag.flags.Strategy;
import com.featureflag.flags.StrategyType;
import com.featureflag.flags.metrics.dto.ConversionRequest;
import com.featureflag.flags.metrics.dto.ExperimentMetricsResponse;
import com.featureflag.flags.metrics.dto.VariationMetrics;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.FieldErrors;

/**
 * Captures impression/conversion telemetry and turns it into per-variation comparison metrics.
 * Reads flag/environment/variation shape from {@link FlagCacheService} (never Mongo directly),
 * the same read path {@link com.featureflag.flags.FlagEvalController} already trusts.
 */
@Service
public class MetricsService {

	private static final int MAX_TEXT_LENGTH = 256;

	private static final double MAX_VALUE_MAGNITUDE = 1e12;

	private final ImpressionRepository impressionRepository;

	private final ConversionRepository conversionRepository;

	private final ImpressionWriter impressionWriter;

	private final FlagCacheService flagCacheService;

	public MetricsService(ImpressionRepository impressionRepository, ConversionRepository conversionRepository,
			ImpressionWriter impressionWriter, FlagCacheService flagCacheService) {
		this.impressionRepository = impressionRepository;
		this.conversionRepository = conversionRepository;
		this.impressionWriter = impressionWriter;
		this.flagCacheService = flagCacheService;
	}

	/**
	 * The "the SDK emits an impression when it calls isEnabled/getVariant" telemetry path. It only
	 * enqueues (see {@link ImpressionWriter}), so the evaluation request never waits on MongoDB and
	 * can never fail because of it; a full queue drops the impression. Conversions and metrics reads
	 * flush the queue first, so a conversion sent right after an evaluation still finds that
	 * impression and inherits the correct experiment inclusion (see {@link #isUserInExperiment}).
	 */
	public void recordImpression(String flagKey, EnvironmentKey environment, String variationKey, String userId, int bucket,
			EvaluationReason reason, boolean includedInExperiment) {
		impressionWriter.enqueue(new ImpressionEvent(flagKey, environment, variationKey, userId, bucket, reason,
				includedInExperiment));
	}

	public ConversionEvent recordConversion(ConversionRequest request) {
		FieldErrors errors = new FieldErrors();

		if (request.flagKey() == null || request.flagKey().isBlank()) {
			errors.add("flagKey", "Flag key is required.");
		}

		if (request.environment() == null) {
			errors.add("environment", "Environment is required.");
		}

		if (request.variationKey() == null || request.variationKey().isBlank()) {
			errors.add("variationKey", "Variation key is required.");
		}

		validateBoundedText(request.userId(), "userId", "User id", errors);
		validateBoundedText(request.eventType(), "eventType", "Event type", errors);

		if (request.value() != null && (!Double.isFinite(request.value()) || Math.abs(request.value()) > MAX_VALUE_MAGNITUDE)) {
			errors.add("value", "Value must be a finite number between -1e12 and 1e12.");
		}

		errors.throwIfAny();

		Flag flag = flagCacheService.get(request.flagKey())
				.filter(candidate -> candidate.getLifecycle() == FlagLifecycle.ACTIVE)
				.orElseThrow(() -> new ApiException(404, "FLAG_NOT_FOUND",
						"No active flag exists with key '" + request.flagKey() + "'."));

		if (!flag.getEnvironments().containsKey(request.environment())) {
			throw new ApiException(404, "ENVIRONMENT_NOT_FOUND",
					"Flag '" + request.flagKey() + "' has no configuration for environment '" + request.environment() + "'.");
		}

		if (!flag.hasVariation(request.variationKey())) {
			throw new ApiException(404, "VARIATION_NOT_FOUND",
					"Flag '" + request.flagKey() + "' has no variation '" + request.variationKey() + "'.");
		}

		return conversionRepository.save(new ConversionEvent(request.flagKey(), request.environment(), request.variationKey(),
				request.userId(), request.eventType(), request.value(), isUserInExperiment(request)));
	}

	private void validateBoundedText(String text, String field, String label, FieldErrors errors) {
		if (text == null || text.isBlank()) {
			errors.add(field, label + " is required.");
		} else if (text.length() > MAX_TEXT_LENGTH) {
			errors.add(field, label + " must be at most " + MAX_TEXT_LENGTH + " characters.");
		}
	}

	/**
	 * A conversion counts toward the experiment only if the user's most recent impression of this flag
	 * did. Without this, conversions from hand-picked users would still land in the numerator while
	 * their impressions were kept out of the denominator, inflating every conversion rate. A user with
	 * no recorded impression yet (an event arriving before, or without, an evaluation) counts as
	 * included, which is how conversions behaved before targeting existed.
	 */
	private boolean isUserInExperiment(ConversionRequest request) {
		impressionWriter.flush();

		return impressionRepository
				.findFirstByFlagKeyAndEnvironmentAndUserIdOrderByOccurredAtDesc(request.flagKey(), request.environment(),
						request.userId())
				.map(ImpressionEvent::isIncludedInExperiment).orElse(true);
	}

	public ExperimentMetricsResponse metrics(String flagKey, EnvironmentKey environment, String eventTypeFilter) {
		Flag flag = flagCacheService.get(flagKey)
				.orElseThrow(() -> new ApiException(404, "FLAG_NOT_FOUND", "No flag exists with key '" + flagKey + "'."));

		if (!flag.getEnvironments().containsKey(environment)) {
			throw new ApiException(404, "ENVIRONMENT_NOT_FOUND",
					"Flag '" + flagKey + "' has no configuration for environment '" + environment + "'.");
		}

		impressionWriter.flush();

		Map<String, Long> impressionCounts = toCounts(impressionRepository.countIncludedByVariation(flagKey, environment));
		Map<String, Long> conversionCounts = toCounts(
				conversionRepository.countIncludedByVariation(flagKey, environment, eventTypeFilter));

		Strategy experimentStrategy = findExperimentStrategy(flag.getEnvironments().get(environment));

		List<VariationMetrics> variationMetrics = flag.getVariations().stream().map(variation -> {
			long impressions = impressionCounts.getOrDefault(variation.getKey(), 0L);
			long conversions = conversionCounts.getOrDefault(variation.getKey(), 0L);
			double conversionRate = impressions == 0 ? 0.0 : (double) conversions / impressions;
			Integer currentWeight = experimentStrategy != null ? experimentStrategy.getVariationWeights().get(variation.getKey())
					: variation.getWeight();

			return new VariationMetrics(variation.getKey(), impressions, conversions, conversionRate, currentWeight);
		}).toList();

		return new ExperimentMetricsResponse(flagKey, environment, variationMetrics,
				experimentStrategy != null ? experimentStrategy.getBanditAlgorithm() : BanditAlgorithm.NONE,
				experimentStrategy != null ? experimentStrategy.getEpsilon() : null,
				impressionRepository.countExcluded(flagKey, environment));
	}

	private Strategy findExperimentStrategy(EnvironmentConfig environmentConfig) {
		return environmentConfig.getStrategies().stream()
				.filter(strategy -> strategy.getType() == StrategyType.EXPERIMENT_SPLIT)
				.findFirst()
				.orElse(null);
	}

	private Map<String, Long> toCounts(List<VariationCount> rows) {
		Map<String, Long> counts = new LinkedHashMap<>();
		rows.forEach(row -> counts.put(row.id(), row.count()));

		return counts;
	}
}
