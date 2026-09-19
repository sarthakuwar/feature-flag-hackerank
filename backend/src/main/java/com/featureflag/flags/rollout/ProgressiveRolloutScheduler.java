package com.featureflag.flags.rollout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically turns progressive rollouts that have reached their final stage into the rule's
 * permanent state. Correctness never depends on this tick: evaluation already serves the new
 * variation to everyone once the final stage begins. The tick only makes that state durable in
 * the flag document and tells connected SDKs about it. Disabled in tests, which drive {@link
 * ProgressiveRolloutService#completeDueRollouts} directly with a controlled clock.
 */
@Component
@ConditionalOnProperty(name = "featureflag.rollout.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class ProgressiveRolloutScheduler {

	private final ProgressiveRolloutService rolloutService;

	public ProgressiveRolloutScheduler(ProgressiveRolloutService rolloutService) {
		this.rolloutService = rolloutService;
	}

	@Scheduled(fixedDelayString = "${featureflag.rollout.scheduler.interval-ms:15000}")
	public void completeDueRollouts() {
		rolloutService.completeDueRollouts();
	}
}
