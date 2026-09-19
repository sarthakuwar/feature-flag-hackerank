package com.featureflag.flags.metrics;

import java.util.List;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;

import com.featureflag.flags.EnvironmentKey;

/** Repository fragment for {@link ConversionRepository}; the only place conversion aggregations touch MongoDB. */
public class ConversionMetricsQueriesImpl implements ConversionMetricsQueries {

	private final MongoTemplate mongoTemplate;

	public ConversionMetricsQueriesImpl(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	@Override
	public List<VariationCount> countIncludedByVariation(String flagKey, EnvironmentKey environment, String eventType) {
		// "not false" rather than "true" so documents written before the field existed still count.
		Criteria criteria = Criteria.where("flagKey").is(flagKey).and("environment").is(environment).and("includedInExperiment")
				.ne(false);

		if (eventType != null && !eventType.isBlank()) {
			criteria = criteria.and("eventType").is(eventType);
		}

		Aggregation aggregation = Aggregation.newAggregation(Aggregation.match(criteria),
				Aggregation.group("variationKey").count().as("count"));

		return mongoTemplate.aggregate(aggregation, ConversionEvent.class, VariationCount.class).getMappedResults();
	}
}
