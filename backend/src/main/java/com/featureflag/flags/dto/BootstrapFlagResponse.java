package com.featureflag.flags.dto;

import java.time.Instant;
import java.util.List;

import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.ProgressiveRollout;
import com.featureflag.flags.RolloutStage;
import com.featureflag.flags.Strategy;
import com.featureflag.flags.Variation;
import com.featureflag.flags.targeting.IndividualTarget;
import com.featureflag.flags.targeting.TargetingRule;

/**
 * What a real SDK fetches once on startup to build its local cache: the flag's shape (not a
 * pre-evaluated result) scoped to a single environment. Only that one environment's targeting
 * rules/rollout are included, not the other two - no reason for a client bootstrapping for
 * PRODUCTION to also receive DEVELOPMENT's targeting rules. It carries exactly what local evaluation
 * needs (including {@code dependsOn} for prerequisites and the schedule of an active progressive
 * rollout) and none of the audit trail: who started or stopped a rollout, when, and the history of
 * finished ones stay on the admin API.
 */
public record BootstrapFlagResponse(String key, String name, FlagType type, List<Variation> variations,
		List<String> dependsOn, Environment environment) {

	/** The evaluated environment; never null, a missing configuration bootstraps as a disabled one. */
	public record Environment(boolean enabled, String offVariationKey, List<Strategy> strategies,
			List<IndividualTarget> individualTargets, List<TargetingRule> rules, List<ActiveRollout> progressiveRollouts) {

		static Environment from(Flag flag, EnvironmentConfig config) {
			if (config == null) {
				String offVariationKey = flag.getVariations().isEmpty() ? null : flag.getVariations().get(0).getKey();

				return new Environment(false, offVariationKey, List.of(), List.of(), List.of(), List.of());
			}

			return new Environment(config.isEnabled(), config.getOffVariationKey(), List.copyOf(config.getStrategies()),
					List.copyOf(config.getIndividualTargets()), List.copyOf(config.getRules()),
					config.getProgressiveRollouts().stream().filter(ProgressiveRollout::active).map(ActiveRollout::from).toList());
		}
	}

	/** An ACTIVE progressive rollout: the ramp schedule only, without who started it or when it was created. */
	public record ActiveRollout(String id, String strategyId, String ruleId, String fromVariationKey, String toVariationKey,
			List<RolloutStage> stages, Instant startAt) {

		static ActiveRollout from(ProgressiveRollout rollout) {
			return new ActiveRollout(rollout.getId(), rollout.getStrategyId(), rollout.getRuleId(),
					rollout.getFromVariationKey(), rollout.getToVariationKey(), List.copyOf(rollout.getStages()),
					rollout.getStartAt());
		}
	}

	public static BootstrapFlagResponse from(Flag flag, EnvironmentConfig environmentConfig) {
		return new BootstrapFlagResponse(flag.getKey(), flag.getName(), flag.getType(), flag.getVariations(),
				flag.getDependsOn() == null ? List.of() : List.copyOf(flag.getDependsOn()),
				Environment.from(flag, environmentConfig));
	}
}
