package com.featureflag.flags;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import com.featureflag.flags.audit.AuditEvent;

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
		// The flag list pages by createdAt then key, with or without a lifecycle filter.
		mongoTemplate.indexOps(Flag.class)
				.ensureIndex(new Index().on("lifecycle", Sort.Direction.ASC).on("createdAt", Sort.Direction.ASC).on("key", Sort.Direction.ASC));
		mongoTemplate.indexOps(Flag.class).ensureIndex(new Index().on("createdAt", Sort.Direction.ASC).on("key", Sort.Direction.ASC));
		// The audit history pages newest first, with the id as tie-break.
		mongoTemplate.indexOps(AuditEvent.class).ensureIndex(
				new Index().on("flagKey", Sort.Direction.ASC).on("changedAt", Sort.Direction.DESC).on("id", Sort.Direction.DESC));
	}
}
