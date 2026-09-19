package com.featureflag.flags;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface FlagRepository extends MongoRepository<Flag, String> {

	Optional<Flag> findByKey(String key);

	boolean existsByKey(String key);

	List<Flag> findByLifecycle(FlagLifecycle lifecycle);

	/** {@code pattern} is a regex fragment matched case-insensitively against key, name and owner; an empty one matches every flag. */
	@Query("{ 'lifecycle': { $in: ?0 }, $or: [ { 'key': { $regex: ?1, $options: 'i' } }, { 'name': { $regex: ?1, $options: 'i' } },"
			+ " { 'owner': { $regex: ?1, $options: 'i' } } ] }")
	Page<Flag> search(List<FlagLifecycle> lifecycles, String pattern, Pageable pageable);
}
