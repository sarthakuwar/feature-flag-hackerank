package com.featureflag.flags.metrics;

import java.util.List;

import com.featureflag.flags.EnvironmentKey;

/** Aggregations over impressions that a derived query method cannot express. Implemented by {@link ImpressionMetricsQueriesImpl}. */
public interface ImpressionMetricsQueries {

	/** Impressions counted toward the experiment, grouped by variation. */
	List<VariationCount> countIncludedByVariation(String flagKey, EnvironmentKey environment);

	/** Impressions left out of the experiment because the user was hand-picked. */
	long countExcluded(String flagKey, EnvironmentKey environment);
}
