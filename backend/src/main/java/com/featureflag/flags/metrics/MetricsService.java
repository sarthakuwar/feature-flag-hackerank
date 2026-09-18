package com.featureflag.flags.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.scheduling.annotation.Async;
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
import com.featureflag.flags.Variation;
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

	private final ImpressionRepository impressionRepository;

	private final ConversionRepository conversionRepository;

	private final MongoTemplate mongoTemplate;

	private final FlagCacheService flagCacheService;

	public MetricsService(ImpressionRepository impressionRepository, ConversionRepository conversionRepository,
			MongoTemplate mongoTemplate, FlagCacheService flagCacheService) {
		this.impressionRepository = impressionRepository;
		this.conversionRepository = conversionRepository;
		this.mongoTemplate = mongoTemplate;
		this.flagCacheService = flagCacheService;
	}

	/**
	 * Fire-and-forget: this is the "the SDK emits an impression when it calls isEnabled/getVariant"
	 * telemetry path. Never allowed to affect the caller - failures are swallowed and logged, not
	 * thrown, since a lost impression is not worth failing a flag evaluation over.
	 */
	@Async
	public void recordImpression(String flagKey, EnvironmentKey environment, String variationKey, String userId, int bucket,
			EvaluationReason reason) {
		try {
			impressionRepository.save(new ImpressionEvent(flagKey, environment, variationKey, userId, bucket, reason));
		} catch (RuntimeException exception) {
			System.err.println("Failed to record impression for flag '" + flagKey + "': " + exception);
		}
	}

	public ConversionEvent recordConversion(ConversionRequest request) {
		FieldErrors errors = new FieldErrors();

		if (request.flagKey() == null || request.flagKey().isBlank()) {
			errors.add("flagKey", "Flag key is required.");
		}

		if (request.environment() == null) {
			errors.add("environment", "Environment is required.");
		}

		if (request.userId() == null || request.userId().isBlank()) {
			errors.add("userId", "User id is required.");
		}

		if (request.eventType() == null || request.eventType().isBlank()) {
			errors.add("eventType", "Event type is required.");
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
				request.userId(), request.eventType(), request.value()));
	}

	public ExperimentMetricsResponse metrics(String flagKey, EnvironmentKey environment, String eventTypeFilter) {
		Flag flag = flagCacheService.get(flagKey)
				.orElseThrow(() -> new ApiException(404, "FLAG_NOT_FOUND", "No flag exists with key '" + flagKey + "'."));

		if (!flag.getEnvironments().containsKey(environment)) {
			throw new ApiException(404, "ENVIRONMENT_NOT_FOUND",
					"Flag '" + flagKey + "' has no configuration for environment '" + environment + "'.");
		}

		Map<String, Long> impressionCounts = countByVariation("flag_impression_events", flagKey, environment, null);
		Map<String, Long> conversionCounts = countByVariation("flag_conversion_events", flagKey, environment, eventTypeFilter);

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
				experimentStrategy != null ? experimentStrategy.getEpsilon() : null);
	}

	private Strategy findExperimentStrategy(EnvironmentConfig environmentConfig) {
		return environmentConfig.getStrategies().stream()
				.filter(strategy -> strategy.getType() == StrategyType.EXPERIMENT_SPLIT)
				.findFirst()
				.orElse(null);
	}

	private Map<String, Long> countByVariation(String collectionName, String flagKey, EnvironmentKey environment,
			String eventTypeFilter) {
		Criteria criteria = Criteria.where("flagKey").is(flagKey).and("environment").is(environment);

		if (eventTypeFilter != null && !eventTypeFilter.isBlank()) {
			criteria = criteria.and("eventType").is(eventTypeFilter);
		}

		Aggregation aggregation = Aggregation.newAggregation(Aggregation.match(criteria),
				Aggregation.group("variationKey").count().as("count"));

		AggregationResults<VariationCount> results = mongoTemplate.aggregate(aggregation, collectionName, VariationCount.class);

		Map<String, Long> counts = new LinkedHashMap<>();
		results.getMappedResults().forEach(result -> counts.put(result.id(), result.count()));

		return counts;
	}

	private record VariationCount(String id, long count) {
	}
}
