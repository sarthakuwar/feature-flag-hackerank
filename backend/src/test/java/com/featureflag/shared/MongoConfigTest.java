package com.featureflag.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.mongodb.MongoClientSettings;

class MongoConfigTest {

	@Test
	void theTimeoutsAreAppliedToTheClientSettings() {
		MongoClientSettings.Builder builder = MongoClientSettings.builder();

		MongoConfig.mongoTimeouts(5_000, 4_000, 30_000).customize(builder);
		MongoClientSettings settings = builder.build();

		assertThat(settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(5_000);
		assertThat(settings.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS)).isEqualTo(4_000);
		assertThat(settings.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(30_000);
	}

	@Test
	void theTimeoutsAreConfigurable() {
		MongoClientSettings.Builder builder = MongoClientSettings.builder();

		MongoConfig.mongoTimeouts(1_000, 2_000, 3_000).customize(builder);
		MongoClientSettings settings = builder.build();

		assertThat(settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(1_000);
		assertThat(settings.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS)).isEqualTo(2_000);
		assertThat(settings.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(3_000);
	}
}
