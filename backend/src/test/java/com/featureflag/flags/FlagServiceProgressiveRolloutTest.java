package com.featureflag.flags;

import static com.featureflag.flags.rollout.RolloutFixtures.RULE_ID;
import static com.featureflag.flags.rollout.RolloutFixtures.T0;
import static com.featureflag.flags.rollout.RolloutFixtures.ended;
import static com.featureflag.flags.rollout.RolloutFixtures.flagWithRule;
import static com.featureflag.flags.rollout.RolloutFixtures.production;
import static com.featureflag.flags.rollout.RolloutFixtures.rollout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.StrategyRequest;
import com.featureflag.shared.ApiException;

/**
 * How environment edits and variation removal behave while a progressive rollout is running: the
 * rollout must survive an edit that rebuilds the environment config, and edits that would
 * invalidate it (dropping its rule, changing what the rule serves, adding an experiment) are refused.
 */
@ExtendWith(MockitoExtension.class)
class FlagServiceProgressiveRolloutTest {

	@Mock
	private FlagRepository flagRepository;

	@Mock
	private FlagCacheService flagCacheService;

	@Mock
	private FlagStreamService flagStreamService;

	@Mock
	private AuditService auditService;

	private FlagService flagService;

	@BeforeEach
	void setUp() {
		FlagAccess flagAccess = new FlagAccess(flagRepository);
		flagService = new FlagService(flagRepository, flagCacheService, flagStreamService, auditService, flagAccess,
				new FlagVariationService(flagRepository, flagCacheService, flagStreamService, auditService, flagAccess),
				new FlagArchiveService(flagRepository, flagCacheService, flagStreamService, auditService, flagAccess));
		lenient().when(flagRepository.save(any(Flag.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private Flag flagWithActiveRollout() {
		Flag flag = flagWithRule("f");
		production(flag).getProgressiveRollouts().add(rollout("r1", RULE_ID, "off", "on", T0));
		when(flagRepository.findByKey("f")).thenReturn(Optional.of(flag));

		return flag;
	}

	private StrategyRequest sameRule() {
		return new StrategyRequest(RULE_ID, StrategyType.ATTRIBUTE, List.of(), "plan", "pro", 0, "off");
	}

	private EnvironmentConfigRequest environment(StrategyRequest... strategies) {
		return new EnvironmentConfigRequest(true, "off", List.of(strategies), "tester");
	}

	private void assertRejectedAsRolloutInProgress(EnvironmentConfigRequest request) {
		assertThatThrownBy(() -> flagService.updateEnvironment("f", EnvironmentKey.PRODUCTION, request))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.getStatusCode()).isEqualTo(409);
					assertThat(exception.getCode()).isEqualTo("ROLLOUT_IN_PROGRESS");
				});
		verify(flagRepository, never()).save(any());
	}

	@Test
	void updatingAnEnvironmentKeepsItsRolloutHistoryAndTheActiveRollout() {
		Flag flag = flagWithActiveRollout();
		ProgressiveRollout old = ended(rollout("old", RULE_ID, "off", "on", T0), RolloutStatus.STOPPED, "off", 25);
		production(flag).getProgressiveRollouts().add(0, old);

		Flag saved = flagService.updateEnvironment("f", EnvironmentKey.PRODUCTION, environment(sameRule()));

		assertThat(production(saved).getProgressiveRollouts()).extracting(ProgressiveRollout::getId).containsExactly("old", "r1");
		assertThat(production(saved).activeProgressiveRollout()).isPresent();
	}

	@Test
	void updatingAnEnvironmentThatNeverHadARolloutLeavesItWithAnEmptyHistory() {
		Flag flag = flagWithRule("f");
		when(flagRepository.findByKey("f")).thenReturn(Optional.of(flag));

		Flag saved = flagService.updateEnvironment("f", EnvironmentKey.DEVELOPMENT, environment(sameRule()));

		assertThat(saved.getEnvironments().get(EnvironmentKey.DEVELOPMENT).getProgressiveRollouts()).isEmpty();
	}

	@Test
	void configuringAnEnvironmentThatHadNoConfigYetStartsWithAnEmptyHistory() {
		Flag flag = flagWithRule("f");
		flag.getEnvironments().remove(EnvironmentKey.DEVELOPMENT);
		when(flagRepository.findByKey("f")).thenReturn(Optional.of(flag));

		Flag saved = flagService.updateEnvironment("f", EnvironmentKey.DEVELOPMENT, environment(sameRule()));

		assertThat(saved.getEnvironments().get(EnvironmentKey.DEVELOPMENT).getProgressiveRollouts()).isEmpty();
	}

	@Test
	void editingTheRulesAudienceWhileARolloutRunsIsAllowed() {
		flagWithActiveRollout();
		StrategyRequest widened = new StrategyRequest(RULE_ID, StrategyType.ATTRIBUTE, List.of(), "plan", "enterprise", 0, "off");

		Flag saved = flagService.updateEnvironment("f", EnvironmentKey.PRODUCTION, environment(widened));

		assertThat(production(saved).getStrategies().get(0).getAttributeValue()).isEqualTo("enterprise");
		assertThat(production(saved).activeProgressiveRollout()).isPresent();
	}

	@Test
	void togglingTheEnvironmentOffAndOnWhileARolloutRunsIsAllowed() {
		flagWithActiveRollout();

		Flag saved = flagService.updateEnvironment("f", EnvironmentKey.PRODUCTION,
				new EnvironmentConfigRequest(false, "off", List.of(sameRule()), "tester"));

		assertThat(production(saved).isEnabled()).isFalse();
		assertThat(production(saved).activeProgressiveRollout()).isPresent();
	}

	@Test
	void addingAnotherRuleWhileARolloutRunsIsAllowed() {
		flagWithActiveRollout();
		StrategyRequest extra = new StrategyRequest(null, StrategyType.USER_ID, List.of("vip"), null, null, 0, "beta");

		Flag saved = flagService.updateEnvironment("f", EnvironmentKey.PRODUCTION, environment(extra, sameRule()));

		assertThat(production(saved).getStrategies()).hasSize(2);
		assertThat(production(saved).activeRolloutFor(RULE_ID)).isPresent();
	}

	@Test
	void reorderingRulesWhileARolloutRunsKeepsTheRolloutAttachedToItsRule() {
		flagWithActiveRollout();
		StrategyRequest extra = new StrategyRequest("vip-rule", StrategyType.USER_ID, List.of("vip"), null, null, 0, "beta");

		Flag saved = flagService.updateEnvironment("f", EnvironmentKey.PRODUCTION, environment(extra, sameRule()));

		Strategy target = production(saved).getStrategies().stream().filter(strategy -> RULE_ID.equals(strategy.getId())).findFirst().orElseThrow();
		assertThat(target.getPriority()).isEqualTo(1);
		assertThat(production(saved).activeRolloutFor(RULE_ID)).isPresent();
	}

	@Test
	void removingTheRuleThatIsBeingRolledOutIsRefused() {
		flagWithActiveRollout();

		assertRejectedAsRolloutInProgress(environment());
	}

	@Test
	void resubmittingTheRuleWithoutItsIdCountsAsRemovingIt() {
		flagWithActiveRollout();
		StrategyRequest anonymous = new StrategyRequest(null, StrategyType.ATTRIBUTE, List.of(), "plan", "pro", 0, "off");

		assertRejectedAsRolloutInProgress(environment(anonymous));
	}

	@Test
	void changingTheVariationTheRuleServesIsRefused() {
		flagWithActiveRollout();
		StrategyRequest changed = new StrategyRequest(RULE_ID, StrategyType.ATTRIBUTE, List.of(), "plan", "pro", 0, "beta");

		assertRejectedAsRolloutInProgress(environment(changed));
	}

	@Test
	void changingTheRulesTypeIsRefused() {
		flagWithActiveRollout();
		StrategyRequest changed = new StrategyRequest(RULE_ID, StrategyType.USER_ID, List.of("a"), null, null, 0, "off");

		assertRejectedAsRolloutInProgress(environment(changed));
	}

	@Test
	void addingAnExperimentWhileARolloutRunsIsRefused() {
		flagWithActiveRollout();
		StrategyRequest experiment = new StrategyRequest(null, StrategyType.EXPERIMENT_SPLIT, List.of(), null, null, 0, null,
				Map.of("off", 34, "on", 33, "beta", 33), BanditAlgorithm.NONE, null);

		assertRejectedAsRolloutInProgress(environment(sameRule(), experiment));
	}

	@Test
	void aRuleWhoseRolloutAlreadyEndedCanBeRewrittenAndRemovedFreely() {
		Flag flag = flagWithRule("f");
		production(flag).getProgressiveRollouts().add(ended(rollout("done", RULE_ID, "off", "on", T0), RolloutStatus.COMPLETED, "on", 100));
		when(flagRepository.findByKey("f")).thenReturn(Optional.of(flag));

		Flag saved = flagService.updateEnvironment("f", EnvironmentKey.PRODUCTION, environment());

		assertThat(production(saved).getStrategies()).isEmpty();
		assertThat(production(saved).getProgressiveRollouts()).hasSize(1);
	}

	@Test
	void aRolloutInOneEnvironmentDoesNotRestrictEditsToAnotherEnvironment() {
		flagWithActiveRollout();

		Flag saved = flagService.updateEnvironment("f", EnvironmentKey.STAGING, environment());

		assertThat(saved.getEnvironments().get(EnvironmentKey.STAGING).getStrategies()).isEmpty();
	}

	@Test
	void theVariationAnActiveRolloutIsMovingTowardCannotBeRemoved() {
		Flag flag = flagWithActiveRollout();
		// "beta" is neither served by a rule nor the off variation, so only the rollout target references it.
		production(flag).getProgressiveRollouts().clear();
		production(flag).getProgressiveRollouts().add(rollout("r1", RULE_ID, "off", "beta", T0));

		assertThatThrownBy(() -> flagService.removeVariation("f", "beta", "tester")).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.getCode()).isEqualTo("VALIDATION_ERROR"));
		assertThat(flag.hasVariation("beta")).isTrue();
	}

	@Test
	void aVariationOnlyAnEndedRolloutMentionedCanBeRemoved() {
		Flag flag = flagWithRule("f");
		production(flag).getProgressiveRollouts().add(ended(rollout("done", RULE_ID, "off", "beta", T0), RolloutStatus.STOPPED, "off", 25));
		when(flagRepository.findByKey("f")).thenReturn(Optional.of(flag));

		Flag saved = flagService.removeVariation("f", "beta", "tester");

		assertThat(saved.hasVariation("beta")).isFalse();
	}
}
