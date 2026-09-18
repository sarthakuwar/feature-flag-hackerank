package com.featureflag.flags;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface FlagRepository extends MongoRepository<Flag, String> {

	Optional<Flag> findByKey(String key);

	boolean existsByKey(String key);

	List<Flag> findByLifecycle(FlagLifecycle lifecycle);
}
