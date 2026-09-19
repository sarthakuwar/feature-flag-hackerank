package com.featureflag.flags.targeting;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Same reason as {@link com.featureflag.flags.FlagIndexInitializer}: auto-index-creation is off, so
 * the uniqueness the API promises (one segment per key per environment, one application per key) is
 * enforced by creating the unique indexes directly on startup.
 */
@Component
public class TargetingIndexInitializer {

	private final MongoTemplate mongoTemplate;

	public TargetingIndexInitializer(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	@PostConstruct
	public void ensureIndexes() {
		mongoTemplate.indexOps(Segment.class)
				.ensureIndex(new Index().on("environment", Sort.Direction.ASC).on("key", Sort.Direction.ASC).unique());
		mongoTemplate.indexOps(MobileApplication.class).ensureIndex(new Index().on("key", Sort.Direction.ASC).unique());
	}
}
