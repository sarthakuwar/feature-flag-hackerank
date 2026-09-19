package com.featureflag.flags.metrics;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Same reason as {@link com.featureflag.flags.FlagIndexInitializer}: auto-index-creation is off, so
 * the indexes the impression and conversion queries need are created directly on startup, together
 * with a TTL index on {@code occurredAt} that expires old telemetry. A retention of zero or less
 * disables expiry and drops an existing TTL index; a changed retention replaces the index, because
 * MongoDB rejects creating one with the same name and a different expiry.
 */
@Component
public class TelemetryIndexInitializer {

	static final String LATEST_BY_USER_INDEX = "flag_env_user_occurred";

	static final String BY_VARIATION_INDEX = "flag_env_variation";

	static final String TTL_INDEX = "occurred_at_ttl";

	private final MongoTemplate mongoTemplate;

	private final long retentionDays;

	public TelemetryIndexInitializer(MongoTemplate mongoTemplate,
			@Value("${featureflag.telemetry.retention-days:${TELEMETRY_RETENTION_DAYS:90}}") long retentionDays) {
		this.mongoTemplate = mongoTemplate;
		this.retentionDays = retentionDays;
	}

	@PostConstruct
	public void ensureIndexes() {
		for (Class<?> type : List.of(ImpressionEvent.class, ConversionEvent.class)) {
			IndexOperations indexes = mongoTemplate.indexOps(type);

			indexes.ensureIndex(new Index().on("flagKey", Sort.Direction.ASC).on("environment", Sort.Direction.ASC)
					.on("userId", Sort.Direction.ASC).on("occurredAt", Sort.Direction.DESC).named(LATEST_BY_USER_INDEX));
			indexes.ensureIndex(new Index().on("flagKey", Sort.Direction.ASC).on("environment", Sort.Direction.ASC)
					.on("variationKey", Sort.Direction.ASC).named(BY_VARIATION_INDEX));
			ensureRetention(indexes);
		}
	}

	private void ensureRetention(IndexOperations indexes) {
		Duration retention = Duration.ofDays(retentionDays);
		boolean enabled = retentionDays > 0;

		for (IndexInfo existing : indexes.getIndexInfo()) {
			boolean stale = !enabled || !retention.equals(existing.getExpireAfter().orElse(null));

			if (TTL_INDEX.equals(existing.getName()) && stale) {
				indexes.dropIndex(TTL_INDEX);
			}
		}

		if (enabled) {
			indexes.ensureIndex(new Index().on("occurredAt", Sort.Direction.ASC).expire(retention).named(TTL_INDEX));
		}
	}
}
