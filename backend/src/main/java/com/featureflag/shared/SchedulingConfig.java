package com.featureflag.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables @Scheduled methods (completing due progressive rollouts). Ships with the Spring
 * context already on the classpath - no new dependency.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
