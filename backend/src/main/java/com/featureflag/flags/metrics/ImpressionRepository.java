package com.featureflag.flags.metrics;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.featureflag.flags.EnvironmentKey;

public interface ImpressionRepository extends MongoRepository<ImpressionEvent, String> {

	List<ImpressionEvent> findByFlagKeyAndEnvironment(String flagKey, EnvironmentKey environment);

	Optional<ImpressionEvent> findFirstByFlagKeyAndEnvironmentAndUserIdOrderByOccurredAtDesc(String flagKey,
			EnvironmentKey environment, String userId);
}
