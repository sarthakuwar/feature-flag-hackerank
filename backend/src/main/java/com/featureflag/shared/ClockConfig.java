package com.featureflag.shared;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of "now" for time-driven behavior (progressive rollout schedules), injected
 * rather than read from {@code Instant.now()} so tests can move time without sleeping.
 */
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
