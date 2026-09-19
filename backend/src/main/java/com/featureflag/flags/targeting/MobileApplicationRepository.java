package com.featureflag.flags.targeting;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface MobileApplicationRepository extends MongoRepository<MobileApplication, String> {

	Optional<MobileApplication> findByKey(String key);

	boolean existsByKey(String key);

	/** The collation makes any sort on the pageable, such as the name, case-insensitive. */
	@Query(value = "{}", collation = "{ 'locale': 'en', 'strength': 2 }")
	Page<MobileApplication> findPage(Pageable pageable);
}
