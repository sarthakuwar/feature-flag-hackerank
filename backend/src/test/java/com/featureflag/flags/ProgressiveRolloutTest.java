package com.featureflag.flags;

import static com.featureflag.flags.rollout.RolloutFixtures.T0;
import static com.featureflag.flags.rollout.RolloutFixtures.stages;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.featureflag.flags.rollout.RolloutFixtures;

/** The schedule maths every evaluation depends on: 25% at start, 50% at +60m, 100% at +120m. */
class ProgressiveRolloutTest {

	private final ProgressiveRollout rollout = RolloutFixtures.rollout("r1", "rule-1", "off", "on", T0);

	@Test
	void percentageIsZeroBeforeTheRolloutStarts() {
		assertThat(rollout.percentageAt(T0.minusSeconds(1))).isZero();
		assertThat(rollout.percentageAt(T0.minus(Duration.ofDays(30)))).isZero();
	}

	@Test
	void firstStageAppliesTheInstantTheRolloutStarts() {
		assertThat(rollout.percentageAt(T0)).isEqualTo(25);
	}

	@Test
	void percentageHoldsUntilTheNextStageOffsetIsReached() {
		assertThat(rollout.percentageAt(T0.plus(Duration.ofMinutes(59)).plusSeconds(59))).isEqualTo(25);
		assertThat(rollout.percentageAt(T0.plus(Duration.ofMinutes(60)))).isEqualTo(50);
		assertThat(rollout.percentageAt(T0.plus(Duration.ofMinutes(119)).plusSeconds(59))).isEqualTo(50);
	}

	@Test
	void percentageIsOneHundredFromTheFinalStageOnward() {
		assertThat(rollout.percentageAt(T0.plus(Duration.ofMinutes(120)))).isEqualTo(100);
		assertThat(rollout.percentageAt(T0.plus(Duration.ofDays(400)))).isEqualTo(100);
	}

	@Test
	void percentageNeverDecreasesAsTimeAdvances() {
		int previous = 0;

		for (int minute = -10; minute <= 200; minute++) {
			int current = rollout.percentageAt(T0.plus(Duration.ofMinutes(minute)));

			assertThat(current).isGreaterThanOrEqualTo(previous);
			previous = current;
		}
	}

	@Test
	void aScheduledStartInTheFutureHoldsAtZeroUntilThen() {
		Instant later = T0.plus(Duration.ofHours(6));
		ProgressiveRollout scheduled = RolloutFixtures.rollout("r2", "rule-1", "off", "on", later);

		assertThat(scheduled.percentageAt(T0.plus(Duration.ofHours(5)))).isZero();
		assertThat(scheduled.percentageAt(later)).isEqualTo(25);
	}

	@Test
	void stageIndexTracksTheStageInEffect() {
		assertThat(rollout.stageIndexAt(T0.minusSeconds(1))).isEqualTo(-1);
		assertThat(rollout.stageIndexAt(T0)).isZero();
		assertThat(rollout.stageIndexAt(T0.plus(Duration.ofMinutes(60)))).isEqualTo(1);
		assertThat(rollout.stageIndexAt(T0.plus(Duration.ofMinutes(500)))).isEqualTo(2);
	}

	@Test
	void nextStageAtReportsWhenTheFollowingStageBegins() {
		assertThat(rollout.nextStageAt(T0.minusSeconds(1))).contains(T0);
		assertThat(rollout.nextStageAt(T0)).contains(T0.plus(Duration.ofMinutes(60)));
		assertThat(rollout.nextStageAt(T0.plus(Duration.ofMinutes(60)))).contains(T0.plus(Duration.ofMinutes(120)));
	}

	@Test
	void nextStageAtIsEmptyOnceTheFinalStageIsReached() {
		assertThat(rollout.nextStageAt(T0.plus(Duration.ofMinutes(120)))).isEmpty();
	}

	@Test
	void completesAtIsWhenTheFinalStageBegins() {
		assertThat(rollout.completesAt()).isEqualTo(T0.plus(Duration.ofMinutes(120)));
	}

	@Test
	void onlyAnActiveStatusCountsAsActive() {
		assertThat(rollout.active()).isTrue();

		rollout.setStatus(RolloutStatus.STOPPED);
		assertThat(rollout.active()).isFalse();

		rollout.setStatus(RolloutStatus.COMPLETED);
		assertThat(rollout.active()).isFalse();
	}

	@Test
	void aSingleFullStageScheduleIsAtOneHundredImmediately() {
		ProgressiveRollout instant = RolloutFixtures.rollout("r3", "rule-1", "off", "on", T0, stages(100, 0));

		assertThat(instant.percentageAt(T0)).isEqualTo(100);
		assertThat(instant.completesAt()).isEqualTo(T0);
	}
}
