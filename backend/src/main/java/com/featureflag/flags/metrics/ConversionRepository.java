package com.featureflag.flags.metrics;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.featureflag.flags.EnvironmentKey;

public interface ConversionRepository extends MongoRepository<ConversionEvent, String> {

	List<ConversionEvent> findByFlagKeyAndEnvironment(String flagKey, EnvironmentKey environment);

	List<ConversionEvent> findByFlagKeyAndEnvironmentAndEventType(String flagKey, EnvironmentKey environment, String eventType);
}
