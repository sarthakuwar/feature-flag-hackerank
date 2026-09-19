package com.featureflag.flags.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TelemetryIndexInitializerIT {

	@Autowired
	private MongoTemplate mongoTemplate;

	@Autowired
	private TelemetryIndexInitializer initializer;

	@AfterEach
	void restoreTheConfiguredIndexes() {
		initializer.ensureIndexes();
	}

	@Test
	void bothCollectionsGetTheQueryIndexesWithTheExpectedKeys() {
		for (Class<?> type : List.of(ImpressionEvent.class, ConversionEvent.class)) {
			assertThat(keysOf(type, TelemetryIndexInitializer.LATEST_BY_USER_INDEX)).containsExactly("flagKey:ASC",
					"environment:ASC", "userId:ASC", "occurredAt:DESC");
			assertThat(keysOf(type, TelemetryIndexInitializer.BY_VARIATION_INDEX)).containsExactly("flagKey:ASC",
					"environment:ASC", "variationKey:ASC");
		}
	}

	@Test
	void bothCollectionsGetATtlIndexOnOccurredAtWithTheConfiguredRetention() {
		for (Class<?> type : List.of(ImpressionEvent.class, ConversionEvent.class)) {
			Optional<IndexInfo> ttl = index(type, TelemetryIndexInitializer.TTL_INDEX);

			assertThat(ttl).isPresent();
			assertThat(keysOf(type, TelemetryIndexInitializer.TTL_INDEX)).containsExactly("occurredAt:ASC");
			assertThat(ttl.get().getExpireAfter()).contains(Duration.ofDays(90));
		}
	}

	@Test
	void creatingTheIndexesTwiceIsIdempotent() {
		initializer.ensureIndexes();
		initializer.ensureIndexes();

		assertThat(index(ImpressionEvent.class, TelemetryIndexInitializer.TTL_INDEX)).isPresent();
	}

	@Test
	void aChangedRetentionReplacesTheTtlIndex() {
		new TelemetryIndexInitializer(mongoTemplate, 30).ensureIndexes();

		for (Class<?> type : List.of(ImpressionEvent.class, ConversionEvent.class)) {
			assertThat(index(type, TelemetryIndexInitializer.TTL_INDEX).orElseThrow().getExpireAfter())
					.contains(Duration.ofDays(30));
		}
	}

	@Test
	void aRetentionOfZeroOrLessDropsTheTtlIndexButKeepsTheQueryIndexes() {
		new TelemetryIndexInitializer(mongoTemplate, 0).ensureIndexes();

		for (Class<?> type : List.of(ImpressionEvent.class, ConversionEvent.class)) {
			assertThat(index(type, TelemetryIndexInitializer.TTL_INDEX)).isEmpty();
			assertThat(index(type, TelemetryIndexInitializer.LATEST_BY_USER_INDEX)).isPresent();
		}

		new TelemetryIndexInitializer(mongoTemplate, -5).ensureIndexes();

		assertThat(index(ImpressionEvent.class, TelemetryIndexInitializer.TTL_INDEX)).isEmpty();
	}

	private Optional<IndexInfo> index(Class<?> type, String name) {
		return mongoTemplate.indexOps(type).getIndexInfo().stream().filter(info -> name.equals(info.getName())).findFirst();
	}

	private List<String> keysOf(Class<?> type, String name) {
		return index(type, name).orElseThrow().getIndexFields().stream().map(this::describe).toList();
	}

	private String describe(IndexField field) {
		return field.getKey() + ":" + field.getDirection();
	}
}
