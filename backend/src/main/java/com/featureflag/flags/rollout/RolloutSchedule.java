package com.featureflag.flags.rollout;

import java.util.ArrayList;
import java.util.List;

import com.featureflag.flags.RolloutStage;
import com.featureflag.flags.rollout.dto.RolloutStageRequest;
import com.featureflag.shared.FieldErrors;

/**
 * Builds and validates progressive rollout schedules. A schedule is an ordered list of stages -
 * "from N minutes in, P% of the audience gets the new variation" - that begins at minute 0, only
 * ever increases, and finishes at exactly 100%. Callers either spell the stages out or ask for an
 * evenly spaced linear ramp by giving a step count and a total duration.
 */
public final class RolloutSchedule {

	public static final int MIN_STAGES = 2;

	public static final int MAX_STAGES = 50;

	public static final int MAX_OFFSET_MINUTES = 60 * 24 * 365;

	private RolloutSchedule() {
	}

	/**
	 * Resolves the request's stages, or its steps+duration shorthand, into a validated schedule.
	 * Exactly one of the two forms must be supplied. Anything wrong is added to {@code errors} and an
	 * empty list is returned.
	 */
	public static List<RolloutStage> resolve(List<RolloutStageRequest> stages, Integer steps, Integer durationMinutes,
			FieldErrors errors) {
		boolean hasStages = stages != null && !stages.isEmpty();
		boolean hasLinear = steps != null || durationMinutes != null;

		if (hasStages && hasLinear) {
			errors.add("stages", "Provide either stages or steps with durationMinutes, not both.");

			return List.of();
		}

		if (!hasStages && !hasLinear) {
			errors.add("stages", "Provide stages, or steps together with durationMinutes.");

			return List.of();
		}

		if (hasLinear) {
			return linear(steps, durationMinutes, errors);
		}

		List<RolloutStage> resolved = new ArrayList<>();

		boolean complete = true;

		for (int index = 0; index < stages.size(); index++) {
			RolloutStageRequest stage = stages.get(index);

			if (stage == null || stage.percentage() == null) {
				errors.add("stages[" + index + "].percentage", "Percentage is required.");
				complete = false;
			}

			if (stage == null || stage.offsetMinutes() == null) {
				errors.add("stages[" + index + "].offsetMinutes", "Offset in minutes is required.");
				complete = false;
			}

			if (complete) {
				resolved.add(new RolloutStage(stage.percentage(), stage.offsetMinutes()));
			}
		}

		if (!complete) {
			return List.of();
		}

		validate(resolved, errors);

		return errors.has("stages") ? List.of() : resolved;
	}

	/**
	 * {@code steps} stages spread evenly over {@code durationMinutes}: percentages climb in equal
	 * increments to 100 (4 steps: 25, 50, 75, 100) and the first stage begins immediately, the last
	 * at the full duration.
	 */
	public static List<RolloutStage> linear(Integer steps, Integer durationMinutes, FieldErrors errors) {
		if (steps == null || durationMinutes == null) {
			errors.add("stages", "Both steps and durationMinutes are required for a linear rollout.");

			return List.of();
		}

		if (steps < MIN_STAGES || steps > MAX_STAGES) {
			errors.add("steps", "Steps must be between " + MIN_STAGES + " and " + MAX_STAGES + ".");
		}

		if (durationMinutes < 1 || durationMinutes > MAX_OFFSET_MINUTES) {
			errors.add("durationMinutes", "Duration must be between 1 and " + MAX_OFFSET_MINUTES + " minutes.");
		} else if (steps >= MIN_STAGES && durationMinutes < steps - 1) {
			errors.add("durationMinutes", "Duration must allow at least one minute between steps ("
					+ (steps - 1) + " minutes for " + steps + " steps).");
		}

		if (errors.has("steps") || errors.has("durationMinutes")) {
			return List.of();
		}

		List<RolloutStage> stages = new ArrayList<>();

		for (int index = 0; index < steps; index++) {
			int percentage = (int) Math.round(100.0 * (index + 1) / steps);
			int offsetMinutes = (int) ((long) durationMinutes * index / (steps - 1));
			stages.add(new RolloutStage(percentage, offsetMinutes));
		}

		return stages;
	}

	public static void validate(List<RolloutStage> stages, FieldErrors errors) {
		if (stages.size() < MIN_STAGES || stages.size() > MAX_STAGES) {
			errors.add("stages", "A rollout needs between " + MIN_STAGES + " and " + MAX_STAGES + " stages.");

			return;
		}

		for (int index = 0; index < stages.size(); index++) {
			RolloutStage stage = stages.get(index);
			String field = "stages[" + index + "]";

			if (stage.getPercentage() < 1 || stage.getPercentage() > 100) {
				errors.add(field + ".percentage", "Percentage must be between 1 and 100.");
			}

			if (stage.getOffsetMinutes() < 0 || stage.getOffsetMinutes() > MAX_OFFSET_MINUTES) {
				errors.add(field + ".offsetMinutes", "Offset must be between 0 and " + MAX_OFFSET_MINUTES + " minutes.");
			}

			if (index == 0 && stage.getOffsetMinutes() != 0) {
				errors.add(field + ".offsetMinutes", "The first stage must start at minute 0.");
			}

			if (index > 0) {
				RolloutStage previous = stages.get(index - 1);

				if (stage.getPercentage() <= previous.getPercentage()) {
					errors.add(field + ".percentage", "Percentages must strictly increase from stage to stage.");
				}

				if (stage.getOffsetMinutes() <= previous.getOffsetMinutes()) {
					errors.add(field + ".offsetMinutes", "Offsets must strictly increase from stage to stage.");
				}
			}
		}

		if (stages.get(stages.size() - 1).getPercentage() != 100) {
			errors.add("stages", "The final stage must reach 100%.");
		}
	}
}
