package com.featureflag.flags.targeting;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.featureflag.flags.EnvironmentKey;

public interface SegmentRepository extends MongoRepository<Segment, String> {

	List<Segment> findByEnvironment(EnvironmentKey environment);

	Optional<Segment> findByEnvironmentAndKey(EnvironmentKey environment, String key);

	boolean existsByEnvironmentAndKey(EnvironmentKey environment, String key);
}
