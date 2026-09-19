package com.featureflag.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.flags.rollout.ProgressiveRolloutService;
import com.featureflag.flags.rollout.dto.StartProgressiveRolloutRequest;
import com.featureflag.shared.ApiException;

/** The seeded baseline includes one live progressive rollout, so a fresh install has one to show. */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@ActiveProfiles("seed-test")
class FlagSeederProgressiveRolloutIT {

	@Autowired
	private FlagRepository flagRepository;

	@Autowired
	private FlagEvaluationService evaluationService;

	@Autowired
	private ProgressiveRolloutService rolloutService;

	private ProgressiveRollout seededRollout() {
		Flag flag = flagRepository.findByKey("dark-mode-default").orElseThrow();

		return flag.getEnvironments().get(EnvironmentKey.PRODUCTION).activeProgressiveRollout().orElseThrow();
	}

	@Test
	void darkModeDefaultIsSeededWithAnActiveWeekLongRolloutInProduction() {
		ProgressiveRollout rollout = seededRollout();

		assertThat(rollout.getStatus()).isEqualTo(RolloutStatus.ACTIVE);
		assertThat(rollout.getFromVariationKey()).isEqualTo("off");
		assertThat(rollout.getToVariationKey()).isEqualTo("on");
		assertThat(rollout.getStages()).extracting(RolloutStage::getPercentage).containsExactly(20, 40, 60, 80, 100);
		assertThat(rollout.getStages().get(4).getOffsetMinutes()).isEqualTo(7 * 24 * 60);
		assertThat(rollout.getStartedBy()).isEqualTo("seed");
	}

	@Test
	void theSeededRolloutTargetsAnExistingRuleThatStillServesTheOldVariation() {
		Flag flag = flagRepository.findByKey("dark-mode-default").orElseThrow();
		EnvironmentConfig production = flag.getEnvironments().get(EnvironmentKey.PRODUCTION);
		Strategy rule = production.getStrategies().stream().filter(strategy -> strategy.getId().equals(seededRollout().getStrategyId()))
				.findFirst().orElseThrow();

		assertThat(rule.getType()).isEqualTo(StrategyType.PERCENTAGE_ROLLOUT);
		assertThat(rule.getServedVariationKey()).isEqualTo("off");
	}

	@Test
	void evaluatingTheSeededFlagPutsARoughlyFifthOfUsersOnTheNewVariationAtTheStart() {
		Flag flag = flagRepository.findByKey("dark-mode-default").orElseThrow();
		int users = 1000;
		long onCount = java.util.stream.IntStream.range(0, users)
				.mapToObj(index -> evaluationService.evaluate(flag, EnvironmentKey.PRODUCTION, new UserContext("user-" + index, Map.of())))
				.filter(result -> "on".equals(result.variationKey())).count();

		assertThat(onCount / (double) users).isBetween(0.14, 0.26);
	}

	@Test
	void theSeededRolloutOccupiesItsEnvironmentSoASecondCannotStart() {
		String ruleId = seededRollout().getStrategyId();

		assertThatThrownBy(() -> rolloutService.start("dark-mode-default", EnvironmentKey.PRODUCTION,
				new StartProgressiveRolloutRequest(ruleId, "off", null, null, 3, 30, "tester")))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo("ROLLOUT_ALREADY_ACTIVE"));
	}

	@Test
	void otherEnvironmentsOfTheSeededFlagHaveNoRollout() {
		Flag flag = flagRepository.findByKey("dark-mode-default").orElseThrow();

		assertThat(flag.getEnvironments().get(EnvironmentKey.DEVELOPMENT).getProgressiveRollouts()).isEmpty();
		assertThat(flag.getEnvironments().get(EnvironmentKey.STAGING).getProgressiveRollouts()).isEmpty();
	}
}
