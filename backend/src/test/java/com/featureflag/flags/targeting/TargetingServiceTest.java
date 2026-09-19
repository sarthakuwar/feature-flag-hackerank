package com.featureflag.flags.targeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagLifecycle;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagStreamService;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.Variation;
import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.targeting.dto.BulkTargetRequest;
import com.featureflag.flags.targeting.dto.BulkTargetResponse;
import com.featureflag.flags.targeting.dto.ClauseRequest;
import com.featureflag.flags.targeting.dto.IndividualTargetRequest;
import com.featureflag.flags.targeting.dto.IndividualTargetsRequest;
import com.featureflag.flags.targeting.dto.RuleEnabledRequest;
import com.featureflag.flags.targeting.dto.RuleMoveRequest;
import com.featureflag.flags.targeting.dto.RuleOrderRequest;
import com.featureflag.flags.targeting.dto.TargetingResponse;
import com.featureflag.flags.targeting.dto.TargetingRuleRequest;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.ValidationException;

@ExtendWith(MockitoExtension.class)
class TargetingServiceTest {

	private static final EnvironmentKey ENV = EnvironmentKey.PRODUCTION;

	@Mock
	private FlagRepository flagRepository;

	@Mock
	private SegmentRepository segmentRepository;

	@Mock
	private FlagCacheService flagCacheService;

	@Mock
	private FlagStreamService flagStreamService;

	@Mock
	private AuditService auditService;

	private TargetingService targetingService;

	private Flag flag;

	@BeforeEach
	void setUp() {
		targetingService = new TargetingService(flagRepository, segmentRepository, flagCacheService, flagStreamService,
				auditService);
		flag = booleanFlag("targeted-flag");
		lenient().when(flagRepository.findByKey("targeted-flag")).thenReturn(Optional.of(flag));
		lenient().when(flagRepository.save(any(Flag.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	// ---- create

	@Test
	void createRuleAppendsSavesRefreshesTheCacheAuditsAndBroadcasts() {
		TargetingRule rule = targetingService.createRule("targeted-flag", ENV, ruleRequest("Pro users", "on", proClause()));

		assertThat(rule.getId()).isNotBlank();
		assertThat(rule.isEnabled()).as("enabled by default").isTrue();
		assertThat(rule.getServedVariationKey()).isEqualTo("on");
		assertThat(config().getRules()).containsExactly(rule);

		verify(flagRepository).save(flag);
		verify(flagCacheService).put(flag);
		verify(auditService).record(eq("targeted-flag"), eq(AuditAction.UPDATE), eq("tester"), anyString());
		verify(flagStreamService).publishFlagChanged(ENV, "targeted-flag", "UPDATE");
	}

	@Test
	void createRuleInsertsAtTheRequestedPosition() {
		targetingService.createRule("targeted-flag", ENV, ruleRequest("first", "on", proClause()));
		targetingService.createRule("targeted-flag", ENV, ruleRequest("second", "on", proClause()));

		targetingService.createRule("targeted-flag", ENV, new TargetingRuleRequest("inserted", null, List.of(proClause()), "on", null, 1, "tester"));

		assertThat(config().getRules()).extracting(TargetingRule::getDescription).containsExactly("first", "inserted", "second");
	}

	@Test
	void createRuleCanStartDisabled() {
		TargetingRule rule = targetingService.createRule("targeted-flag", ENV,
				new TargetingRuleRequest("draft", false, List.of(proClause()), "on", null, null, "tester"));

		assertThat(rule.isEnabled()).isFalse();
	}

	@Test
	void createRuleRejectsAPositionOutsideTheList() {
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				new TargetingRuleRequest("x", null, List.of(proClause()), "on", null, 5, "tester")))).containsKey("position");
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				new TargetingRuleRequest("x", null, List.of(proClause()), "on", null, -1, "tester")))).containsKey("position");
	}

	@Test
	void createRuleRequiresAtLeastOneClauseAndChangedBy() {
		Map<String, List<String>> errors = fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				new TargetingRuleRequest("x", null, List.of(), "on", null, null, " ")));

		assertThat(errors).containsKeys("clauses", "changedBy");
	}

	@Test
	void createRuleRequiresExactlyOneOfAFixedVariationOrARollout() {
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				new TargetingRuleRequest("x", null, List.of(proClause()), null, null, null, "tester")))).containsKey("servedVariationKey");
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				new TargetingRuleRequest("x", null, List.of(proClause()), "on", Map.of("off", 50, "on", 50), null, "tester"))))
				.containsKey("servedVariationKey");
	}

	@Test
	void createRuleRejectsAnUnknownServedVariation() {
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV, ruleRequest("x", "ghost", proClause()))))
				.containsKey("servedVariationKey");
	}

	@Test
	void createRuleValidatesRolloutWeights() {
		assertThat(fieldErrors(() -> createRollout(Map.of("off", 40, "on", 40)))).as("sums to 80").containsKey("rolloutWeights");
		assertThat(fieldErrors(() -> createRollout(Map.of("off", 50, "ghost", 50)))).as("unknown variation").containsKey("rolloutWeights");
		assertThat(fieldErrors(() -> createRollout(Map.of("off", -10, "on", 110)))).as("out of range").containsKey("rolloutWeights");
	}

	@Test
	void createRuleAcceptsAValidRollout() {
		TargetingRule rule = createRollout(new LinkedHashMap<>(Map.of("off", 30, "on", 70)));

		assertThat(rule.servesRollout()).isTrue();
		assertThat(rule.getServedVariationKey()).isNull();
		assertThat(rule.getRolloutWeights()).containsEntry("on", 70);
	}

	@Test
	void createRuleRejectsAnUnknownSegmentAndAcceptsAKnownOne() {
		Segment beta = segment("beta-testers", "user");
		when(segmentRepository.findByEnvironmentAndKey(ENV, "beta-testers")).thenReturn(Optional.of(beta));
		when(segmentRepository.findByEnvironmentAndKey(ENV, "ghost")).thenReturn(Optional.empty());

		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				ruleRequest("x", "on", segmentClause("ghost"))))).containsKey("clauses[0].values");

		TargetingRule rule = targetingService.createRule("targeted-flag", ENV, ruleRequest("x", "on", segmentClause("beta-testers")));

		assertThat(rule.category()).isEqualTo(RuleCategory.SEGMENT);
	}

	@Test
	void createRuleRejectsASegmentOfADifferentContextKind() {
		when(segmentRepository.findByEnvironmentAndKey(ENV, "orgs")).thenReturn(Optional.of(segment("orgs", "organization")));

		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				ruleRequest("x", "on", segmentClause("orgs"))))).containsKey("clauses[0].values");
	}

	@Test
	void createRuleValidatesEachOperatorsValues() {
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				ruleRequest("x", "on", clause("age", ClauseOperator.GREATER_THAN, "old"))))).containsKey("clauses[0].values");
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				ruleRequest("x", "on", clause("v", ClauseOperator.SEMVER_GREATER_THAN, "banana"))))).containsKey("clauses[0].values");
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				ruleRequest("x", "on", clause("e", ClauseOperator.MATCHES, "([bad"))))).containsKey("clauses[0].values");
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				ruleRequest("x", "on", clause("e", ClauseOperator.MATCHES, "a".repeat(TargetingLimits.MAX_REGEX_LENGTH + 1)))))).containsKey("clauses[0].values");
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				ruleRequest("x", "on", new ClauseRequest("user", null, ClauseOperator.IN, List.of("a"), false))))).containsKey("clauses[0].attribute");
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				ruleRequest("x", "on", new ClauseRequest("user", "plan", ClauseOperator.IN, List.of(), false))))).containsKey("clauses[0].values");
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				ruleRequest("x", "on", new ClauseRequest("user", "plan", null, List.of("a"), false))))).containsKey("clauses[0].operator");
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				ruleRequest("x", "on", new ClauseRequest("Bad Kind!", "plan", ClauseOperator.IN, List.of("a"), false))))).containsKey("clauses[0].contextKind");
	}

	@Test
	void appVersionSupportIsOnlyValidOnTheApplicationContextKind() {
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV,
				ruleRequest("x", "on", new ClauseRequest("user", null, ClauseOperator.APP_VERSION_SUPPORTED, null, true)))))
				.containsKey("clauses[0].contextKind");

		TargetingRule rule = targetingService.createRule("targeted-flag", ENV,
				ruleRequest("unsupported", "on", new ClauseRequest("ld_application", null, ClauseOperator.APP_VERSION_SUPPORTED, null, true)));

		assertThat(rule.category()).isEqualTo(RuleCategory.MOBILE);
	}

	@Test
	void duplicateClauseValuesAreCollapsed() {
		TargetingRule rule = targetingService.createRule("targeted-flag", ENV,
				ruleRequest("x", "on", clause("plan", ClauseOperator.IN, "pro", "pro", "free")));

		assertThat(rule.getClauses().get(0).getValues()).containsExactly("pro", "free");
	}

	@Test
	void ruleCategoryIsDerivedFromItsClauses() {
		assertThat(targetingService.createRule("targeted-flag", ENV, ruleRequest("custom", "on", proClause())).category())
				.isEqualTo(RuleCategory.CUSTOM);
		assertThat(targetingService.createRule("targeted-flag", ENV,
				ruleRequest("mobile", "on", new ClauseRequest("ld_device", "os.name", ClauseOperator.IN, List.of("Android"), false))).category())
				.isEqualTo(RuleCategory.MOBILE);
	}

	@Test
	void createRuleIsRefusedOnAnArchivedFlagWithoutSavingAnything() {
		flag.setLifecycle(FlagLifecycle.ARCHIVED);

		assertThatThrownBy(() -> targetingService.createRule("targeted-flag", ENV, ruleRequest("x", "on", proClause())))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo("FLAG_ARCHIVED"));
		verify(flagRepository, never()).save(any());
		verify(flagStreamService, never()).publishFlagChanged(any(), any(), any());
	}

	@Test
	void unknownFlagAndMissingEnvironmentAreNotFound() {
		when(flagRepository.findByKey("ghost")).thenReturn(Optional.empty());
		flag.getEnvironments().remove(EnvironmentKey.STAGING);

		assertThatThrownBy(() -> targetingService.createRule("ghost", ENV, ruleRequest("x", "on", proClause())))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getStatusCode()).isEqualTo(404));
		assertThatThrownBy(() -> targetingService.createRule("targeted-flag", EnvironmentKey.STAGING, ruleRequest("x", "on", proClause())))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo("ENVIRONMENT_NOT_FOUND"));
	}

	@Test
	void aFailedValidationSavesAndBroadcastsNothing() {
		assertThatThrownBy(() -> targetingService.createRule("targeted-flag", ENV, ruleRequest("x", "ghost", proClause())))
				.isInstanceOf(ValidationException.class);

		verify(flagRepository, never()).save(any());
		verify(flagCacheService, never()).put(any());
		verify(auditService, never()).record(any(), any(), any(), any());
		assertThat(config().getRules()).isEmpty();
	}

	// ---- limits

	@Test
	void aFlagEnvironmentCannotExceedTheRuleLimit() {
		for (int index = 0; index < TargetingLimits.MAX_RULES_PER_ENVIRONMENT; index++) {
			config().getRules().add(rule("r" + index, "on"));
		}

		assertThatThrownBy(() -> targetingService.createRule("targeted-flag", ENV, ruleRequest("one-too-many", "on", proClause())))
				.isInstanceOf(ValidationException.class);
		assertThat(config().getRules()).hasSize(TargetingLimits.MAX_RULES_PER_ENVIRONMENT);
	}

	@Test
	void aFlagEnvironmentCannotExceedTheValueLimitAcrossAllRules() {
		TargetingRule heavy = rule("heavy", "on");
		heavy.getClauses().add(new Clause("user", "id", ClauseOperator.IN, values(TargetingLimits.MAX_VALUES_PER_ENVIRONMENT - 5), false));
		config().getRules().add(heavy);

		targetingService.createRule("targeted-flag", ENV,
				ruleRequest("fits", "on", new ClauseRequest("user", "id", ClauseOperator.IN, values(5), false)));

		assertThatThrownBy(() -> targetingService.createRule("targeted-flag", ENV,
				ruleRequest("over", "on", new ClauseRequest("user", "id", ClauseOperator.IN, values(1), false))))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void replacingARuleDoesNotDoubleCountItsOwnValuesAgainstTheLimit() {
		TargetingRule heavy = rule("heavy", "on");
		heavy.getClauses().add(new Clause("user", "id", ClauseOperator.IN, values(TargetingLimits.MAX_VALUES_PER_ENVIRONMENT), false));
		config().getRules().add(heavy);

		TargetingRule updated = targetingService.updateRule("targeted-flag", ENV, "heavy",
				ruleRequest("still-maxed", "on", new ClauseRequest("user", "id", ClauseOperator.IN, values(TargetingLimits.MAX_VALUES_PER_ENVIRONMENT), false)));

		assertThat(updated.valueCount()).isEqualTo(TargetingLimits.MAX_VALUES_PER_ENVIRONMENT);
	}

	// ---- update / enable / delete

	@Test
	void updateRuleReplacesConditionsInPlaceKeepingIdPositionAndEnabledState() {
		TargetingRule first = targetingService.createRule("targeted-flag", ENV, ruleRequest("first", "on", proClause()));
		TargetingRule second = targetingService.createRule("targeted-flag", ENV, ruleRequest("second", "on", proClause()));
		targetingService.setRuleEnabled("targeted-flag", ENV, first.getId(), new RuleEnabledRequest(false, "tester"));

		TargetingRule updated = targetingService.updateRule("targeted-flag", ENV, first.getId(),
				ruleRequest("renamed", "off", clause("country", ClauseOperator.IN, "US")));

		assertThat(updated.getId()).isEqualTo(first.getId());
		assertThat(updated.getDescription()).isEqualTo("renamed");
		assertThat(updated.getServedVariationKey()).isEqualTo("off");
		assertThat(updated.isEnabled()).as("enabled: null keeps the current state").isFalse();
		assertThat(config().getRules()).containsExactly(updated, second);
	}

	@Test
	void updateRuleCanSwitchFromAFixedVariationToARollout() {
		TargetingRule created = targetingService.createRule("targeted-flag", ENV, ruleRequest("x", "on", proClause()));

		TargetingRule updated = targetingService.updateRule("targeted-flag", ENV, created.getId(),
				new TargetingRuleRequest("x", null, List.of(proClause()), null, Map.of("off", 90, "on", 10), null, "tester"));

		assertThat(updated.getServedVariationKey()).isNull();
		assertThat(updated.servesRollout()).isTrue();
	}

	@Test
	void updatingOrDeletingAnUnknownRuleIsNotFound() {
		assertThatThrownBy(() -> targetingService.updateRule("targeted-flag", ENV, "ghost", ruleRequest("x", "on", proClause())))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo("RULE_NOT_FOUND"));
		assertThatThrownBy(() -> targetingService.deleteRule("targeted-flag", ENV, "ghost", "tester"))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo("RULE_NOT_FOUND"));
		assertThatThrownBy(() -> targetingService.setRuleEnabled("targeted-flag", ENV, "ghost", new RuleEnabledRequest(false, "tester")))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo("RULE_NOT_FOUND"));
	}

	@Test
	void disablingAndEnablingARuleKeepsItAndOnlyPersistsRealChanges() {
		TargetingRule rule = targetingService.createRule("targeted-flag", ENV, ruleRequest("x", "on", proClause()));

		targetingService.setRuleEnabled("targeted-flag", ENV, rule.getId(), new RuleEnabledRequest(false, "tester"));

		assertThat(rule.isEnabled()).isFalse();
		assertThat(config().getRules()).contains(rule);
		verify(auditService).record(eq("targeted-flag"), eq(AuditAction.UPDATE), eq("tester"), org.mockito.ArgumentMatchers.contains("Disabled"));

		org.mockito.Mockito.clearInvocations(flagRepository, flagStreamService);
		targetingService.setRuleEnabled("targeted-flag", ENV, rule.getId(), new RuleEnabledRequest(false, "tester"));

		verify(flagRepository, never()).save(any());
		verify(flagStreamService, never()).publishFlagChanged(any(), any(), any());

		targetingService.setRuleEnabled("targeted-flag", ENV, rule.getId(), new RuleEnabledRequest(true, "tester"));

		assertThat(rule.isEnabled()).isTrue();
	}

	@Test
	void deleteRuleRemovesItAndReturnsTheRemainingState() {
		TargetingRule first = targetingService.createRule("targeted-flag", ENV, ruleRequest("first", "on", proClause()));
		TargetingRule second = targetingService.createRule("targeted-flag", ENV, ruleRequest("second", "on", proClause()));

		TargetingResponse response = targetingService.deleteRule("targeted-flag", ENV, first.getId(), "tester");

		assertThat(response.rules()).containsExactly(second);
		assertThat(response.limits().ruleCount()).isEqualTo(1);
	}

	// ---- ordering

	@Test
	void reorderRulesAppliesTheGivenOrder() {
		TargetingRule a = targetingService.createRule("targeted-flag", ENV, ruleRequest("a", "on", proClause()));
		TargetingRule b = targetingService.createRule("targeted-flag", ENV, ruleRequest("b", "on", proClause()));
		TargetingRule c = targetingService.createRule("targeted-flag", ENV, ruleRequest("c", "on", proClause()));

		TargetingResponse response = targetingService.reorderRules("targeted-flag", ENV,
				new RuleOrderRequest(List.of(c.getId(), a.getId(), b.getId()), "tester"));

		assertThat(response.rules()).extracting(TargetingRule::getDescription).containsExactly("c", "a", "b");
	}

	@Test
	void reorderRulesMustBeAPermutationOfTheExistingIds() {
		TargetingRule a = targetingService.createRule("targeted-flag", ENV, ruleRequest("a", "on", proClause()));
		TargetingRule b = targetingService.createRule("targeted-flag", ENV, ruleRequest("b", "on", proClause()));

		assertThat(fieldErrors(() -> targetingService.reorderRules("targeted-flag", ENV, new RuleOrderRequest(List.of(a.getId()), "tester"))))
				.as("missing one").containsKey("ruleIds");
		assertThat(fieldErrors(() -> targetingService.reorderRules("targeted-flag", ENV, new RuleOrderRequest(List.of(a.getId(), a.getId()), "tester"))))
				.as("duplicate").containsKey("ruleIds");
		assertThat(fieldErrors(() -> targetingService.reorderRules("targeted-flag", ENV, new RuleOrderRequest(List.of(a.getId(), "ghost"), "tester"))))
				.as("unknown id").containsKey("ruleIds");
		assertThat(config().getRules()).containsExactly(a, b);
	}

	@Test
	void reorderingToTheCurrentOrderSavesAndBroadcastsNothing() {
		TargetingRule a = targetingService.createRule("targeted-flag", ENV, ruleRequest("a", "on", proClause()));
		TargetingRule b = targetingService.createRule("targeted-flag", ENV, ruleRequest("b", "on", proClause()));
		org.mockito.Mockito.clearInvocations(flagRepository, flagStreamService);

		targetingService.reorderRules("targeted-flag", ENV, new RuleOrderRequest(List.of(a.getId(), b.getId()), "tester"));

		verify(flagRepository, never()).save(any());
		verify(flagStreamService, never()).publishFlagChanged(any(), any(), any());
	}

	@Test
	void moveRuleUpAndDownSwapsWithItsNeighbour() {
		TargetingRule a = targetingService.createRule("targeted-flag", ENV, ruleRequest("a", "on", proClause()));
		TargetingRule b = targetingService.createRule("targeted-flag", ENV, ruleRequest("b", "on", proClause()));
		TargetingRule c = targetingService.createRule("targeted-flag", ENV, ruleRequest("c", "on", proClause()));

		targetingService.moveRule("targeted-flag", ENV, c.getId(), new RuleMoveRequest(MoveDirection.UP, "tester"));

		assertThat(config().getRules()).containsExactly(a, c, b);

		targetingService.moveRule("targeted-flag", ENV, a.getId(), new RuleMoveRequest(MoveDirection.DOWN, "tester"));

		assertThat(config().getRules()).containsExactly(c, a, b);
	}

	@Test
	void movingTheFirstRuleUpOrTheLastRuleDownIsAQuietNoOp() {
		TargetingRule a = targetingService.createRule("targeted-flag", ENV, ruleRequest("a", "on", proClause()));
		TargetingRule b = targetingService.createRule("targeted-flag", ENV, ruleRequest("b", "on", proClause()));
		org.mockito.Mockito.clearInvocations(flagRepository, flagStreamService);

		targetingService.moveRule("targeted-flag", ENV, a.getId(), new RuleMoveRequest(MoveDirection.UP, "tester"));
		targetingService.moveRule("targeted-flag", ENV, b.getId(), new RuleMoveRequest(MoveDirection.DOWN, "tester"));

		assertThat(config().getRules()).containsExactly(a, b);
		verify(flagRepository, never()).save(any());
		verify(flagStreamService, never()).publishFlagChanged(any(), any(), any());
	}

	@Test
	void moveRuleRequiresADirection() {
		TargetingRule a = targetingService.createRule("targeted-flag", ENV, ruleRequest("a", "on", proClause()));

		assertThat(fieldErrors(() -> targetingService.moveRule("targeted-flag", ENV, a.getId(), new RuleMoveRequest(null, "tester"))))
				.containsKey("direction");
	}

	// ---- individual targets

	@Test
	void replaceIndividualTargetsBuildsOneEntryPerKindAndVariationWithCleanedKeys() {
		TargetingResponse response = targetingService.replaceIndividualTargets("targeted-flag", ENV, new IndividualTargetsRequest(List.of(
				new IndividualTargetRequest(null, "on", List.of(" u1 ", "u2", "u1", "", "  ")),
				new IndividualTargetRequest("user", "on", List.of("u3")),
				new IndividualTargetRequest("organization", "off", List.of("org-1")),
				new IndividualTargetRequest("user", "off", List.of())), "tester"));

		assertThat(response.individualTargets()).hasSize(2);
		IndividualTarget users = response.individualTargets().get(0);
		assertThat(users.getContextKind()).isEqualTo("user");
		assertThat(users.getVariationKey()).isEqualTo("on");
		assertThat(users.getKeys()).containsExactly("u1", "u2", "u3");
		assertThat(response.individualTargets().get(1).getContextKind()).isEqualTo("organization");
		assertThat(response.limits().individualKeyCount()).isEqualTo(4);
	}

	@Test
	void replaceIndividualTargetsRejectsAKeyTargetedToTwoVariations() {
		assertThat(fieldErrors(() -> targetingService.replaceIndividualTargets("targeted-flag", ENV, new IndividualTargetsRequest(List.of(
				new IndividualTargetRequest("user", "on", List.of("u1")),
				new IndividualTargetRequest("user", "off", List.of("u1"))), "tester")))).containsKey("targets[1].keys");
	}

	@Test
	void theSameKeyMayBeTargetedInDifferentContextKinds() {
		TargetingResponse response = targetingService.replaceIndividualTargets("targeted-flag", ENV, new IndividualTargetsRequest(List.of(
				new IndividualTargetRequest("user", "on", List.of("shared")),
				new IndividualTargetRequest("organization", "off", List.of("shared"))), "tester"));

		assertThat(response.individualTargets()).hasSize(2);
	}

	@Test
	void replaceIndividualTargetsRejectsUnknownVariationsAndBadKinds() {
		assertThat(fieldErrors(() -> targetingService.replaceIndividualTargets("targeted-flag", ENV, new IndividualTargetsRequest(List.of(
				new IndividualTargetRequest("user", "ghost", List.of("u1"))), "tester")))).containsKey("targets[0].variationKey");
		assertThat(fieldErrors(() -> targetingService.replaceIndividualTargets("targeted-flag", ENV, new IndividualTargetsRequest(List.of(
				new IndividualTargetRequest("BAD KIND", "on", List.of("u1"))), "tester")))).containsKey("targets[0].contextKind");
	}

	@Test
	void replaceIndividualTargetsWithAnEmptyListClearsThem() {
		config().getIndividualTargets().add(new IndividualTarget("user", "on", Set.of("u1")));

		TargetingResponse response = targetingService.replaceIndividualTargets("targeted-flag", ENV,
				new IndividualTargetsRequest(List.of(), "tester"));

		assertThat(response.individualTargets()).isEmpty();
	}

	// ---- bulk targeting

	@Test
	void bulkAddTargetsManyKeysAtOnceAndReportsWhatChanged() {
		BulkTargetResponse response = targetingService.bulkTarget("targeted-flag", ENV,
				new BulkTargetRequest("user", "on", BulkOperation.ADD, List.of("u1", "u2", " u3 ", "u2", ""), "tester"));

		assertThat(response.summary().requested()).as("deduped and trimmed").isEqualTo(3);
		assertThat(response.summary().changed()).isEqualTo(3);
		assertThat(response.summary().unchanged()).isZero();
		assertThat(response.targeting().individualTargets().get(0).getKeys()).containsExactly("u1", "u2", "u3");
		verify(flagStreamService).publishFlagChanged(ENV, "targeted-flag", "UPDATE");
	}

	@Test
	void bulkAddMovesAKeyAwayFromTheVariationItWasPreviouslyTargetedTo() {
		config().getIndividualTargets().add(new IndividualTarget("user", "off", Set.of("u1", "u2")));

		BulkTargetResponse response = targetingService.bulkTarget("targeted-flag", ENV,
				new BulkTargetRequest("user", "on", BulkOperation.ADD, List.of("u1"), "tester"));

		assertThat(response.summary().changed()).isEqualTo(1);
		assertThat(byVariation(response.targeting(), "on").getKeys()).containsExactly("u1");
		assertThat(byVariation(response.targeting(), "off").getKeys()).containsExactly("u2");
	}

	@Test
	void bulkAddOfKeysAlreadyTargetedIsAReportedNoOpThatSavesNothing() {
		config().getIndividualTargets().add(new IndividualTarget("user", "on", Set.of("u1", "u2")));

		BulkTargetResponse response = targetingService.bulkTarget("targeted-flag", ENV,
				new BulkTargetRequest("user", "on", BulkOperation.ADD, List.of("u1", "u2"), "tester"));

		assertThat(response.summary().changed()).isZero();
		assertThat(response.summary().unchanged()).isEqualTo(2);
		verify(flagRepository, never()).save(any());
		verify(flagStreamService, never()).publishFlagChanged(any(), any(), any());
	}

	@Test
	void bulkRemoveOnlyRemovesFromTheNamedVariationAndDropsEmptyTargets() {
		config().getIndividualTargets().add(new IndividualTarget("user", "on", Set.of("u1", "u2")));
		config().getIndividualTargets().add(new IndividualTarget("user", "off", Set.of("u3")));

		BulkTargetResponse first = targetingService.bulkTarget("targeted-flag", ENV,
				new BulkTargetRequest("user", "on", BulkOperation.REMOVE, List.of("u1", "u3", "nobody"), "tester"));

		assertThat(first.summary().changed()).as("u3 is on 'off', nobody isn't targeted").isEqualTo(1);
		assertThat(byVariation(first.targeting(), "on").getKeys()).containsExactly("u2");
		assertThat(byVariation(first.targeting(), "off").getKeys()).containsExactly("u3");

		BulkTargetResponse second = targetingService.bulkTarget("targeted-flag", ENV,
				new BulkTargetRequest("user", "on", BulkOperation.REMOVE, List.of("u2"), "tester"));

		assertThat(second.targeting().individualTargets()).extracting(IndividualTarget::getVariationKey).containsExactly("off");
	}

	@Test
	void bulkTargetingIsScopedToTheContextKind() {
		config().getIndividualTargets().add(new IndividualTarget("organization", "off", Set.of("shared")));

		BulkTargetResponse response = targetingService.bulkTarget("targeted-flag", ENV,
				new BulkTargetRequest("user", "on", BulkOperation.ADD, List.of("shared"), "tester"));

		assertThat(response.summary().changed()).isEqualTo(1);
		assertThat(response.targeting().individualTargets()).hasSize(2);
		assertThat(response.targeting().individualTargets()).allSatisfy(target -> assertThat(target.getKeys()).containsExactly("shared"));
	}

	@Test
	void bulkTargetValidatesItsRequest() {
		assertThat(fieldErrors(() -> targetingService.bulkTarget("targeted-flag", ENV,
				new BulkTargetRequest("user", "ghost", BulkOperation.ADD, List.of("u1"), "tester")))).containsKey("variationKey");
		assertThat(fieldErrors(() -> targetingService.bulkTarget("targeted-flag", ENV,
				new BulkTargetRequest("user", "on", null, List.of("u1"), "tester")))).containsKey("operation");
		assertThat(fieldErrors(() -> targetingService.bulkTarget("targeted-flag", ENV,
				new BulkTargetRequest("user", "on", BulkOperation.ADD, List.of(" ", ""), "tester")))).containsKey("keys");
		assertThat(fieldErrors(() -> targetingService.bulkTarget("targeted-flag", ENV,
				new BulkTargetRequest("user", "on", BulkOperation.ADD, null, "tester")))).containsKey("keys");
		assertThat(fieldErrors(() -> targetingService.bulkTarget("targeted-flag", ENV,
				new BulkTargetRequest("user", "on", BulkOperation.ADD, List.of("u1"), null)))).containsKey("changedBy");
	}

	@Test
	void bulkTargetEnforcesTheIndividualKeyLimit() {
		config().getIndividualTargets().add(new IndividualTarget("user", "off", new java.util.LinkedHashSet<>(values(TargetingLimits.MAX_INDIVIDUAL_KEYS_PER_ENVIRONMENT))));

		assertThatThrownBy(() -> targetingService.bulkTarget("targeted-flag", ENV,
				new BulkTargetRequest("user", "on", BulkOperation.ADD, List.of("brand-new-key"), "tester")))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void bulkTargetAcceptsTensOfThousandsOfKeysInOneCall() {
		List<String> keys = values(40_000);

		BulkTargetResponse response = targetingService.bulkTarget("targeted-flag", ENV,
				new BulkTargetRequest("user", "on", BulkOperation.ADD, keys, "tester"));

		assertThat(response.summary().changed()).isEqualTo(40_000);
		assertThat(response.targeting().limits().individualKeyCount()).isEqualTo(40_000);
	}

	@Test
	void getReturnsTargetingStateAndLimitsEvenForAnArchivedFlag() {
		targetingService.createRule("targeted-flag", ENV, ruleRequest("x", "on", proClause()));
		flag.setLifecycle(FlagLifecycle.ARCHIVED);

		TargetingResponse response = targetingService.get("targeted-flag", ENV);

		assertThat(response.rules()).hasSize(1);
		assertThat(response.limits().maxRules()).isEqualTo(5_000);
		assertThat(response.limits().maxValues()).isEqualTo(50_000);
		assertThat(response.limits().valueCount()).isEqualTo(1);
	}

	// ---- include in experiment

	@Test
	void aRuleIsNotIncludedInTheExperimentByDefault() {
		assertThat(createRollout(Map.of("off", 50, "on", 50)).isIncludeInExperiment()).isFalse();
		assertThat(targetingService.createRule("targeted-flag", ENV, ruleRequest("fixed", "on", proClause())).isIncludeInExperiment()).isFalse();
	}

	@Test
	void aRolloutRuleCanBeIncludedInTheExperiment() {
		assertThat(createIncludedRollout().isIncludeInExperiment()).isTrue();
	}

	@Test
	void onlyARolloutRuleMayBeIncludedInTheExperiment() {
		assertThat(fieldErrors(() -> targetingService.createRule("targeted-flag", ENV, new TargetingRuleRequest("fixed", null,
				List.of(proClause()), "on", null, null, "tester", true)))).containsKey("includeInExperiment");
		assertThat(config().getRules()).isEmpty();
	}

	@Test
	void updateWithoutTheFieldKeepsTheRulesCurrentChoice() {
		TargetingRule created = createIncludedRollout();

		TargetingRule updated = targetingService.updateRule("targeted-flag", ENV, created.getId(), new TargetingRuleRequest("renamed",
				null, List.of(proClause()), null, Map.of("off", 20, "on", 80), null, "tester"));

		assertThat(updated.isIncludeInExperiment()).isTrue();
	}

	@Test
	void updateCanOptOutAgainAndOptBackIn() {
		TargetingRule created = createIncludedRollout();

		TargetingRule optedOut = targetingService.updateRule("targeted-flag", ENV, created.getId(), new TargetingRuleRequest("in",
				null, List.of(proClause()), null, Map.of("off", 50, "on", 50), null, "tester", false));
		assertThat(optedOut.isIncludeInExperiment()).isFalse();

		TargetingRule optedIn = targetingService.updateRule("targeted-flag", ENV, created.getId(), new TargetingRuleRequest("in",
				null, List.of(proClause()), null, Map.of("off", 50, "on", 50), null, "tester", true));
		assertThat(optedIn.isIncludeInExperiment()).isTrue();
	}

	@Test
	void switchingAnIncludedRolloutToAFixedVariationDropsTheInclusionInsteadOfCarryingItOver() {
		TargetingRule created = createIncludedRollout();

		TargetingRule updated = targetingService.updateRule("targeted-flag", ENV, created.getId(),
				ruleRequest("now fixed", "on", proClause()));

		assertThat(updated.servesRollout()).isFalse();
		assertThat(updated.isIncludeInExperiment()).isFalse();
	}

	@Test
	void explicitlyIncludingWhileSwitchingToAFixedVariationIsRejected() {
		TargetingRule created = createIncludedRollout();

		assertThat(fieldErrors(() -> targetingService.updateRule("targeted-flag", ENV, created.getId(), new TargetingRuleRequest(
				"now fixed", null, List.of(proClause()), "on", null, null, "tester", true)))).containsKey("includeInExperiment");
	}

	private TargetingRule createIncludedRollout() {
		return targetingService.createRule("targeted-flag", ENV, new TargetingRuleRequest("in", null, List.of(proClause()), null,
				Map.of("off", 50, "on", 50), null, "tester", true));
	}

	// ---- helpers

	private TargetingRule createRollout(Map<String, Integer> weights) {
		return targetingService.createRule("targeted-flag", ENV,
				new TargetingRuleRequest("rollout", null, List.of(proClause()), null, weights, null, "tester"));
	}

	private EnvironmentConfig config() {
		return flag.getEnvironments().get(ENV);
	}

	private IndividualTarget byVariation(TargetingResponse response, String variationKey) {
		return response.individualTargets().stream().filter(target -> target.getVariationKey().equals(variationKey)).findFirst()
				.orElseThrow();
	}

	private static List<String> values(int count) {
		return IntStream.range(0, count).mapToObj(index -> "v" + index).collect(Collectors.toList());
	}

	private static ClauseRequest proClause() {
		return clause("plan", ClauseOperator.IN, "pro");
	}

	private static ClauseRequest clause(String attribute, ClauseOperator operator, String... values) {
		return new ClauseRequest("user", attribute, operator, List.of(values), false);
	}

	private static ClauseRequest segmentClause(String... segmentKeys) {
		return new ClauseRequest("user", null, ClauseOperator.IN_SEGMENT, List.of(segmentKeys), false);
	}

	private static TargetingRuleRequest ruleRequest(String description, String servedVariationKey, ClauseRequest... clauses) {
		return new TargetingRuleRequest(description, null, List.of(clauses), servedVariationKey, null, null, "tester");
	}

	private static TargetingRule rule(String id, String servedVariationKey) {
		TargetingRule rule = new TargetingRule();
		rule.setId(id);
		rule.setServedVariationKey(servedVariationKey);
		rule.setClauses(new ArrayList<>());

		return rule;
	}

	private static Segment segment(String key, String contextKind) {
		Segment segment = new Segment();
		segment.setEnvironment(ENV);
		segment.setKey(key);
		segment.setName(key);
		segment.setContextKind(contextKind);

		return segment;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, List<String>> fieldErrors(org.junit.jupiter.api.function.Executable executable) {
		ValidationException thrown = org.junit.jupiter.api.Assertions.assertThrows(ValidationException.class, executable);

		return (Map<String, List<String>>) thrown.getDetails().get("fieldErrors");
	}

	private static Flag booleanFlag(String key) {
		Flag flag = new Flag();
		flag.setKey(key);
		flag.setName("Targeted Flag");
		flag.setType(FlagType.BOOLEAN);
		flag.setLifecycle(FlagLifecycle.ACTIVE);
		flag.setOwner("owner");
		flag.setVariations(new ArrayList<>(List.of(new Variation("off", "Off", "false"), new Variation("on", "On", "true"))));

		Map<EnvironmentKey, EnvironmentConfig> environments = new EnumMap<>(EnvironmentKey.class);

		for (EnvironmentKey environment : EnvironmentKey.values()) {
			environments.put(environment, new EnvironmentConfig(false, "off", new ArrayList<>()));
		}

		flag.setEnvironments(environments);

		return flag;
	}
}
