package com.featureflag.flags.targeting.dto;

import java.util.List;

import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.targeting.IndividualTarget;
import com.featureflag.flags.targeting.TargetingLimits;
import com.featureflag.flags.targeting.TargetingRule;

/** One environment's whole targeting picture, in evaluation order, plus how close it is to the caps. */
public record TargetingResponse(List<IndividualTarget> individualTargets, List<TargetingRule> rules, Limits limits) {

	public record Limits(int maxRules, int maxValues, int maxIndividualKeys, int ruleCount, int valueCount,
			int individualKeyCount) {
	}

	public static TargetingResponse from(EnvironmentConfig config) {
		int valueCount = config.getRules().stream().mapToInt(TargetingRule::valueCount).sum();
		int keyCount = config.getIndividualTargets().stream().mapToInt(target -> target.getKeys().size()).sum();

		return new TargetingResponse(config.getIndividualTargets(), config.getRules(),
				new Limits(TargetingLimits.MAX_RULES_PER_ENVIRONMENT, TargetingLimits.MAX_VALUES_PER_ENVIRONMENT,
						TargetingLimits.MAX_INDIVIDUAL_KEYS_PER_ENVIRONMENT, config.getRules().size(), valueCount, keyCount));
	}
}
