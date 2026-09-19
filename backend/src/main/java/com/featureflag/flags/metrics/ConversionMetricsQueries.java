package com.featureflag.flags.metrics;

import java.util.List;

import com.featureflag.flags.EnvironmentKey;

/** Aggregations over conversions that a derived query method cannot express. Implemented by {@link ConversionMetricsQueriesImpl}. */
public interface ConversionMetricsQueries {

	/** Conversions counted toward the experiment, grouped by variation; a blank event type matches every type. */
	List<VariationCount> countIncludedByVariation(String flagKey, EnvironmentKey environment, String eventType);
}
