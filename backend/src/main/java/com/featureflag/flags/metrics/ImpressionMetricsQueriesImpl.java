package com.featureflag.flags.metrics;

import java.util.List;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.featureflag.flags.EnvironmentKey;

/** Repository fragment for {@link ImpressionRepository}; the only place impression aggregations touch MongoDB. */
public class ImpressionMetricsQueriesImpl implements ImpressionMetricsQueries {

	private final MongoTemplate mongoTemplate;

	public ImpressionMetricsQueriesImpl(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	@Override
	public List<VariationCount> countIncludedByVariation(String flagKey, EnvironmentKey environment) {
		// "not false" rather than "true" so documents written before the field existed still count.
		Criteria criteria = Criteria.where("flagKey").is(flagKey).and("environment").is(environment).and("includedInExperiment")
				.ne(false);
		Aggregation aggregation = Aggregation.newAggregation(Aggregation.match(criteria),
				Aggregation.group("variationKey").count().as("count"));

		return mongoTemplate.aggregate(aggregation, ImpressionEvent.class, VariationCount.class).getMappedResults();
	}

	@Override
	public long countExcluded(String flagKey, EnvironmentKey environment) {
		Criteria criteria = Criteria.where("flagKey").is(flagKey).and("environment").is(environment).and("includedInExperiment")
				.is(false);

		return mongoTemplate.count(new Query(criteria), ImpressionEvent.class);
	}
}
