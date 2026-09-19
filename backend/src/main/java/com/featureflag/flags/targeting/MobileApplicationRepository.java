package com.featureflag.flags.targeting;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MobileApplicationRepository extends MongoRepository<MobileApplication, String> {

	Optional<MobileApplication> findByKey(String key);

	boolean existsByKey(String key);
}
