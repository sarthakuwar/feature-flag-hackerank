package com.featureflag.shared;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

@Configuration
@EnableMongoAuditing
public class MongoConfig {

	public MongoConfig(MappingMongoConverter converter) {
		converter.setTypeMapper(new DefaultMongoTypeMapper(null));
	}

	/**
	 * Bounded waits so an unreachable or stalled MongoDB fails a request in seconds instead of
	 * hanging it: the driver's defaults are 30 seconds to select a server and no socket timeout.
	 * Static because the Mongo client is built from this bean while this class still needs the
	 * converter that depends on that client.
	 */
	@Bean
	public static MongoClientSettingsBuilderCustomizer mongoTimeouts(
			@Value("${featureflag.mongo.server-selection-timeout-ms:5000}") long serverSelectionTimeoutMillis,
			@Value("${featureflag.mongo.connect-timeout-ms:5000}") int connectTimeoutMillis,
			@Value("${featureflag.mongo.socket-timeout-ms:30000}") int socketTimeoutMillis) {
		return builder -> builder
				.applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(serverSelectionTimeoutMillis, TimeUnit.MILLISECONDS))
				.applyToSocketSettings(socket -> socket.connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
						.readTimeout(socketTimeoutMillis, TimeUnit.MILLISECONDS));
	}
}
