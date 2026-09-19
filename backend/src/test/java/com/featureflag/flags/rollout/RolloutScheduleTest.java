package com.featureflag.flags.rollout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.featureflag.flags.RolloutStage;
import com.featureflag.flags.rollout.dto.RolloutStageRequest;
import com.featureflag.shared.FieldErrors;
import com.featureflag.shared.ValidationException;

class RolloutScheduleTest {

	private final FieldErrors errors = new FieldErrors();

	@Test
	void linearSpreadsPercentagesAndOffsetsEvenly() {
		List<RolloutStage> stages = RolloutSchedule.linear(4, 60, errors);

		assertThat(errors.isEmpty()).isTrue();
		assertThat(stages).extracting(RolloutStage::getPercentage).containsExactly(25, 50, 75, 100);
		assertThat(stages).extracting(RolloutStage::getOffsetMinutes).containsExactly(0, 20, 40, 60);
	}

	@Test
	void linearWithTwoStepsIsHalfThenFull() {
		List<RolloutStage> stages = RolloutSchedule.linear(2, 10, errors);

		assertThat(stages).extracting(RolloutStage::getPercentage).containsExactly(50, 100);
		assertThat(stages).extracting(RolloutStage::getOffsetMinutes).containsExactly(0, 10);
	}

	@Test
	void linearRoundsUnevenPercentagesButAlwaysEndsAtOneHundred() {
		List<RolloutStage> stages = RolloutSchedule.linear(3, 90, errors);

		assertThat(stages).extracting(RolloutStage::getPercentage).containsExactly(33, 67, 100);
		assertThat(stages).extracting(RolloutStage::getOffsetMinutes).containsExactly(0, 45, 90);
	}

	@Test
	void linearProducesAValidScheduleForEveryAllowedStepCount() {
		for (int steps = RolloutSchedule.MIN_STAGES; steps <= RolloutSchedule.MAX_STAGES; steps++) {
			FieldErrors local = new FieldErrors();
			List<RolloutStage> stages = RolloutSchedule.linear(steps, steps - 1, local);

			assertThat(local.isEmpty()).as("steps=%d", steps).isTrue();
			assertThat(stages).hasSize(steps);

			FieldErrors validation = new FieldErrors();
			RolloutSchedule.validate(stages, validation);
			assertThat(validation.isEmpty()).as("generated schedule for %d steps validates", steps).isTrue();
		}
	}

	@Test
	void linearOverAWeekEndsExactlyAtTheRequestedDuration() {
		int week = 7 * 24 * 60;
		List<RolloutStage> stages = RolloutSchedule.linear(10, week, errors);

		assertThat(stages.get(0).getOffsetMinutes()).isZero();
		assertThat(stages.get(9).getOffsetMinutes()).isEqualTo(week);
		assertThat(stages.get(9).getPercentage()).isEqualTo(100);
	}

	@Test
	void linearRejectsStepCountsOutsideTheAllowedRange() {
		assertThat(RolloutSchedule.linear(1, 60, errors)).isEmpty();
		assertThat(errors.has("steps")).isTrue();

		FieldErrors tooMany = new FieldErrors();
		assertThat(RolloutSchedule.linear(RolloutSchedule.MAX_STAGES + 1, 600, tooMany)).isEmpty();
		assertThat(tooMany.has("steps")).isTrue();
	}

	@Test
	void linearRejectsDurationsTooShortToSeparateTheSteps() {
		assertThat(RolloutSchedule.linear(5, 3, errors)).isEmpty();

		assertThat(errors.has("durationMinutes")).isTrue();
	}

	@Test
	void linearRejectsNonPositiveAndExcessiveDurations() {
		assertThat(RolloutSchedule.linear(4, 0, errors)).isEmpty();
		assertThat(errors.has("durationMinutes")).isTrue();

		FieldErrors excessive = new FieldErrors();
		assertThat(RolloutSchedule.linear(4, RolloutSchedule.MAX_OFFSET_MINUTES + 1, excessive)).isEmpty();
		assertThat(excessive.has("durationMinutes")).isTrue();
	}

	@Test
	void linearRequiresBothStepsAndDuration() {
		assertThat(RolloutSchedule.linear(4, null, errors)).isEmpty();
		assertThat(RolloutSchedule.linear(null, 60, errors)).isEmpty();

		assertThat(errors.has("stages")).isTrue();
	}

	@Test
	void resolveAcceptsExplicitStages() {
		List<RolloutStage> stages = RolloutSchedule.resolve(
				List.of(new RolloutStageRequest(10, 0), new RolloutStageRequest(60, 30), new RolloutStageRequest(100, 90)), null,
				null, errors);

		assertThat(errors.isEmpty()).isTrue();
		assertThat(stages).extracting(RolloutStage::getPercentage).containsExactly(10, 60, 100);
		assertThat(stages).extracting(RolloutStage::getOffsetMinutes).containsExactly(0, 30, 90);
	}

	@Test
	void resolveAcceptsTheLinearShorthand() {
		List<RolloutStage> stages = RolloutSchedule.resolve(null, 4, 60, errors);

		assertThat(errors.isEmpty()).isTrue();
		assertThat(stages).hasSize(4);
	}

	@Test
	void resolveRequiresEveryStageToNameItsPercentageAndOffset() {
		List<RolloutStage> stages = RolloutSchedule.resolve(
				List.of(new RolloutStageRequest(null, 0), new RolloutStageRequest(50, null), new RolloutStageRequest(100, 10)), null, null,
				errors);

		assertThat(stages).isEmpty();
		assertThat(errors.has("stages[0].percentage")).isTrue();
		assertThat(errors.has("stages[1].offsetMinutes")).isTrue();
		assertThat(errors.has("stages[2].percentage")).isFalse();
	}

	@Test
	void resolveTreatsAnEmptyStageListAsAbsent() {
		List<RolloutStage> stages = RolloutSchedule.resolve(List.of(), 4, 60, errors);

		assertThat(errors.isEmpty()).isTrue();
		assertThat(stages).hasSize(4);
	}

	@Test
	void resolveRejectsBothFormsAtOnce() {
		List<RolloutStage> stages = RolloutSchedule.resolve(
				List.of(new RolloutStageRequest(50, 0), new RolloutStageRequest(100, 10)), 4, 60, errors);

		assertThat(stages).isEmpty();
		assertThat(errors.has("stages")).isTrue();
	}

	@Test
	void resolveRejectsNeitherForm() {
		assertThat(RolloutSchedule.resolve(null, null, null, errors)).isEmpty();
		assertThat(errors.has("stages")).isTrue();
	}

	@Test
	void resolveReturnsNothingWhenExplicitStagesAreInvalid() {
		List<RolloutStage> stages = RolloutSchedule.resolve(
				List.of(new RolloutStageRequest(50, 0), new RolloutStageRequest(40, 10)), null, null, errors);

		assertThat(stages).isEmpty();
		assertThat(errors.isEmpty()).isFalse();
	}

	@Test
	void validateRejectsASingleStage() {
		RolloutSchedule.validate(stages(100, 0), errors);

		assertThat(errors.has("stages")).isTrue();
	}

	@Test
	void validateRejectsMoreStagesThanAllowed() {
		List<RolloutStage> tooMany = new ArrayList<>();
		IntStream.rangeClosed(0, RolloutSchedule.MAX_STAGES).forEach(index -> tooMany.add(new RolloutStage(index + 1, index)));

		RolloutSchedule.validate(tooMany, errors);

		assertThat(errors.has("stages")).isTrue();
	}

	@Test
	void validateRejectsAFirstStageThatDoesNotStartAtMinuteZero() {
		RolloutSchedule.validate(stages(10, 5, 100, 60), errors);

		assertThat(errors.has("stages[0].offsetMinutes")).isTrue();
	}

	@Test
	void validateRejectsPercentagesThatDoNotStrictlyIncrease() {
		RolloutSchedule.validate(stages(50, 0, 50, 10, 100, 20), errors);

		assertThat(errors.has("stages[1].percentage")).isTrue();
	}

	@Test
	void validateRejectsADecreasingPercentage() {
		RolloutSchedule.validate(stages(50, 0, 30, 10, 100, 20), errors);

		assertThat(errors.has("stages[1].percentage")).isTrue();
	}

	@Test
	void validateRejectsOffsetsThatDoNotStrictlyIncrease() {
		RolloutSchedule.validate(stages(10, 0, 50, 30, 100, 30), errors);

		assertThat(errors.has("stages[2].offsetMinutes")).isTrue();
	}

	@Test
	void validateRejectsAFinalStageBelowOneHundred() {
		RolloutSchedule.validate(stages(10, 0, 90, 30), errors);

		assertThat(errors.has("stages")).isTrue();
	}

	@Test
	void validateRejectsOutOfRangePercentages() {
		RolloutSchedule.validate(stages(0, 0, 101, 30), errors);

		assertThat(errors.has("stages[0].percentage")).isTrue();
		assertThat(errors.has("stages[1].percentage")).isTrue();
	}

	@Test
	void validateRejectsNegativeAndExcessiveOffsets() {
		RolloutSchedule.validate(stages(10, -1, 100, RolloutSchedule.MAX_OFFSET_MINUTES + 1), errors);

		assertThat(errors.has("stages[0].offsetMinutes")).isTrue();
		assertThat(errors.has("stages[1].offsetMinutes")).isTrue();
	}

	@Test
	void validateAcceptsAWellFormedCustomSchedule() {
		RolloutSchedule.validate(stages(1, 0, 5, 15, 20, 60, 100, 1440), errors);

		assertThat(errors.isEmpty()).isTrue();
	}

	@Test
	void collectedErrorsSurfaceAsAValidationException() {
		RolloutSchedule.resolve(null, null, null, errors);

		assertThatThrownBy(errors::throwIfAny).isInstanceOf(ValidationException.class);
	}

	private static List<RolloutStage> stages(int... pairs) {
		return RolloutFixtures.stages(pairs);
	}
}
