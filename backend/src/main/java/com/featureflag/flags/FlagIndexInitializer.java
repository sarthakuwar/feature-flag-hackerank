package com.featureflag.flags;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * spring.data.mongodb.auto-index-creation defaults to false (and application.properties is
 * read-only here), so @Indexed(unique = true) on Flag.key alone would never actually create the
 * index. Flag keys must be unique across the entire system (the spec is explicit about this), so
 * the unique index is created directly against MongoTemplate on startup instead.
 */
@Component
public class FlagIndexInitializer {

	private final MongoTemplate mongoTemplate;

	public FlagIndexInitializer(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	@PostConstruct
	public void ensureIndexes() {
		mongoTemplate.indexOps(Flag.class).ensureIndex(new Index().on("key", Sort.Direction.ASC).unique());
	}
}
