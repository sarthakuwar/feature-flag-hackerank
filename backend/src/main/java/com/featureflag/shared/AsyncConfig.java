package com.featureflag.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables @Async methods (impression recording) so evaluation never waits on telemetry I/O.
 * Ships with spring-boot-starter-web's Spring context support - no new dependency.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
