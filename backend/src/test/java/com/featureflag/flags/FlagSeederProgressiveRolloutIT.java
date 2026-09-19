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

	private ProgressiveRollout seededRuleRollout() {
		Flag flag = flagRepository.findByKey("pricing-page-redesign").orElseThrow();

		return flag.getEnvironments().get(EnvironmentKey.STAGING).activeProgressiveRollout().orElseThrow();
	}

	@Test
	void pricingPageRedesignIsSeededWithARolloutRampingATargetingRuleInStaging() {
		ProgressiveRollout rollout = seededRuleRollout();
		Flag flag = flagRepository.findByKey("pricing-page-redesign").orElseThrow();
		com.featureflag.flags.targeting.TargetingRule rule = flag.getEnvironments().get(EnvironmentKey.STAGING).getRules().stream()
				.filter(candidate -> candidate.getId().equals(rollout.getRuleId())).findFirst().orElseThrow();

		assertThat(rollout.targetsRule()).isTrue();
		assertThat(rollout.getStrategyId()).isNull();
		assertThat(rollout.getFromVariationKey()).isEqualTo("control");
		assertThat(rollout.getToVariationKey()).isEqualTo("new-hero");
		assertThat(rollout.getStages()).hasSize(4);
		assertThat(rule.getServedVariationKey()).as("the rule still serves the old variation until the ramp ends").isEqualTo("control");
	}

	@Test
	void theSeededRuleRolloutRampsOnlyTheStaffAudienceAndAtTheStartMovesARoughlyQuarterOfThem() {
		Flag flag = flagRepository.findByKey("pricing-page-redesign").orElseThrow();
		long onCount = java.util.stream.IntStream.range(0, 1000)
				.mapToObj(index -> evaluationService.evaluate(flag, EnvironmentKey.STAGING,
						new UserContext("staff-" + index, Map.of("email", "staff-" + index + "@featureflag.dev"))))
				.peek(result -> assertThat(result.reason()).isEqualTo(EvaluationReason.PROGRESSIVE_ROLLOUT))
				.filter(result -> "new-hero".equals(result.variationKey())).count();

		assertThat(onCount / 1000.0).isBetween(0.18, 0.32);
		assertThat(evaluationService.evaluate(flag, EnvironmentKey.STAGING, new UserContext("outsider", Map.of("email", "x@example.com")))
				.reason()).as("non-staff are not ramped").isNotEqualTo(EvaluationReason.PROGRESSIVE_ROLLOUT);
	}

	@Test
	void theSeededRuleRolloutOccupiesStagingButNotOtherEnvironmentsOfThatFlag() {
		String ruleId = seededRuleRollout().getRuleId();

		assertThatThrownBy(() -> rolloutService.start("pricing-page-redesign", EnvironmentKey.STAGING,
				StartProgressiveRolloutRequest.forRule(ruleId, "minimal", null, null, 3, 30, "tester")))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo("ROLLOUT_ALREADY_ACTIVE"));

		Flag flag = flagRepository.findByKey("pricing-page-redesign").orElseThrow();
		assertThat(flag.getEnvironments().get(EnvironmentKey.PRODUCTION).getProgressiveRollouts()).isEmpty();
		assertThat(flag.getEnvironments().get(EnvironmentKey.DEVELOPMENT).getProgressiveRollouts()).isEmpty();
	}

	@Test
	void otherEnvironmentsOfTheSeededFlagHaveNoRollout() {
		Flag flag = flagRepository.findByKey("dark-mode-default").orElseThrow();

		assertThat(flag.getEnvironments().get(EnvironmentKey.DEVELOPMENT).getProgressiveRollouts()).isEmpty();
		assertThat(flag.getEnvironments().get(EnvironmentKey.STAGING).getProgressiveRollouts()).isEmpty();
	}
}
