package com.featureflag.flags;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.featureflag.flags.dto.StrategyRequest;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.FieldErrors;
import com.featureflag.shared.Validation;

/** Write-time checks for an environment's legacy strategies, and the guard for saving them during a rollout. */
final class StrategyValidator {

	private StrategyValidator() {
	}

	static List<Strategy> validate(List<StrategyRequest> requested, Set<String> variationKeys, FieldErrors errors) {
		List<Strategy> strategies = new ArrayList<>();
		List<StrategyRequest> requests = requested == null ? List.of() : requested;

		for (int index = 0; index < requests.size(); index++) {
			StrategyRequest strategyRequest = requests.get(index);
			String field = "strategies[" + index + "]";

			if (strategyRequest.type() == null) {
				errors.add(field + ".type", "Strategy type is required.");
			} else if (strategyRequest.type() == StrategyType.USER_ID
					&& (strategyRequest.userIds() == null || strategyRequest.userIds().isEmpty())) {
				errors.add(field + ".userIds", "At least one user id is required for a USER_ID strategy.");
			} else if (strategyRequest.type() == StrategyType.ATTRIBUTE) {
				Validation.requireText(strategyRequest.attribute(), field + ".attribute", errors);
				Validation.requireText(strategyRequest.attributeValue(), field + ".attributeValue", errors);
			} else if (strategyRequest.type() == StrategyType.PERCENTAGE_ROLLOUT && strategyRequest.percentage() == null) {
				errors.add(field + ".percentage", "Percentage is required for a PERCENTAGE_ROLLOUT strategy.");
			} else if (strategyRequest.type() == StrategyType.PERCENTAGE_ROLLOUT
					&& (strategyRequest.percentage() < 0 || strategyRequest.percentage() > 100)) {
				errors.add(field + ".percentage", "Percentage must be between 0 and 100.");
			} else if (strategyRequest.type() == StrategyType.EXPERIMENT_SPLIT) {
				validateExperimentSplit(strategyRequest, variationKeys, field, errors);
			}

			if (strategyRequest.type() != StrategyType.EXPERIMENT_SPLIT) {
				Validation.validateVariationReference(strategyRequest.servedVariationKey(), variationKeys,
						field + ".servedVariationKey", errors);
			}

			Strategy strategy = new Strategy();
			strategy.setId(strategyRequest.id() == null || strategyRequest.id().isBlank() ? UUID.randomUUID().toString()
					: strategyRequest.id());
			strategy.setPriority(index);
			strategy.setType(strategyRequest.type());
			strategy.setUserIds(strategyRequest.userIds() == null ? List.of() : strategyRequest.userIds());
			strategy.setAttribute(strategyRequest.attribute());
			strategy.setAttributeValue(strategyRequest.attributeValue());
			strategy.setPercentage(strategyRequest.percentage() == null ? 0 : strategyRequest.percentage());
			strategy.setServedVariationKey(strategyRequest.servedVariationKey());
			strategy.setVariationWeights(
					strategyRequest.variationWeights() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(strategyRequest.variationWeights()));
			strategy.setBanditAlgorithm(strategyRequest.banditAlgorithm() == null ? BanditAlgorithm.NONE : strategyRequest.banditAlgorithm());
			strategy.setEpsilon(strategyRequest.epsilon());
			strategies.add(strategy);
		}

		return strategies;
	}

	/**
	 * servedVariationKey is meaningless for EXPERIMENT_SPLIT (variationWeights decides the outcome
	 * for every bucket), so it's validated separately here instead of via
	 * {@link Validation#validateVariationReference}.
	 */
	private static void validateExperimentSplit(StrategyRequest strategyRequest, Set<String> variationKeys, String field,
			FieldErrors errors) {
		Map<String, Integer> weights = strategyRequest.variationWeights();

		if (weights == null || weights.isEmpty()) {
			errors.add(field + ".variationWeights", "Variation weights are required for an EXPERIMENT_SPLIT strategy.");
		} else {
			if (!weights.keySet().equals(variationKeys)) {
				errors.add(field + ".variationWeights", "Weights must cover exactly this flag's variations.");
			}

			int sum = weights.values().stream().mapToInt(weight -> weight == null ? 0 : weight).sum();

			if (sum != 100) {
				errors.add(field + ".variationWeights", "Variation weights must sum to 100 (found " + sum + ").");
			}
		}

		BanditAlgorithm algorithm = strategyRequest.banditAlgorithm() == null ? BanditAlgorithm.NONE : strategyRequest.banditAlgorithm();

		if (algorithm == BanditAlgorithm.EPSILON_GREEDY) {
			Double epsilon = strategyRequest.epsilon();

			if (epsilon == null || epsilon <= 0 || epsilon > 1) {
				errors.add(field + ".epsilon", "Epsilon is required and must be between 0 (exclusive) and 1 (inclusive) for EPSILON_GREEDY.");
			}
		} else if (strategyRequest.epsilon() != null) {
			errors.add(field + ".epsilon", "Epsilon only applies to the EPSILON_GREEDY algorithm.");
		}
	}

	/**
	 * While a progressive rollout is running, the environment's rules can be edited freely except in
	 * the ways that would invalidate it: dropping the targeted rule, changing its type or the
	 * variation it serves (the rollout's "from"), or introducing an experiment. Stopping the
	 * rollout first is how a caller decides what the rule serves instead.
	 */
	static void requireCompatibleWithActiveRollout(EnvironmentConfig existing, List<Strategy> requested) {
		if (existing == null) {
			return;
		}

		ProgressiveRollout rollout = existing.activeProgressiveRollout().orElse(null);

		if (rollout == null) {
			return;
		}

		if (requested.stream().anyMatch(strategy -> strategy.getType() == StrategyType.EXPERIMENT_SPLIT)) {
			throw new ApiException(409, "ROLLOUT_IN_PROGRESS",
					"An experiment cannot be added while a progressive rollout is active; stop the rollout first.");
		}

		if (rollout.targetsRule()) {
			// A targeting rule is not part of this request (rules have their own endpoints, guarded in
			// TargetingService), so there is nothing else about the rollout this save could invalidate.
			return;
		}

		Strategy target = requested.stream().filter(strategy -> rollout.getStrategyId().equals(strategy.getId())).findFirst()
				.orElse(null);

		if (target == null) {
			throw new ApiException(409, "ROLLOUT_IN_PROGRESS",
					"Rule '" + rollout.getStrategyId() + "' has an active progressive rollout and cannot be removed; stop the rollout first.");
		}

		Strategy current = existing.getStrategies().stream().filter(strategy -> rollout.getStrategyId().equals(strategy.getId()))
				.findFirst().orElse(null);

		boolean changed = current != null && (current.getType() != target.getType()
				|| !Objects.equals(current.getServedVariationKey(), target.getServedVariationKey()));

		if (changed) {
			throw new ApiException(409, "ROLLOUT_IN_PROGRESS", "Rule '" + rollout.getStrategyId()
					+ "' has an active progressive rollout, so its type and served variation cannot change; stop the rollout first.");
		}
	}
}
