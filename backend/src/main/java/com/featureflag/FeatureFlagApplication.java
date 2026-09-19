package com.featureflag;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FeatureFlagApplication {

	/**
	 * Operational defaults that application.properties (read-only) cannot carry. Explicit
	 * configuration, such as an environment variable, still wins over them. Graceful shutdown lets
	 * in-flight requests finish; streams are closed first by FlagStreamService.
	 */
	static Map<String, Object> defaultProperties() {
		return Map.of("server.shutdown", "graceful", "spring.lifecycle.timeout-per-shutdown-phase", "20s");
	}

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(FeatureFlagApplication.class);
		application.setDefaultProperties(defaultProperties());
		application.run(args);
	}
}
