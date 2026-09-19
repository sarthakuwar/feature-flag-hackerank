package com.featureflag.flags.targeting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.featureflag.flags.ContextEntry;
import com.featureflag.flags.EnvironmentConfig;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.EvaluationReason;
import com.featureflag.flags.EvaluationResult;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagEvaluationService;
import com.featureflag.flags.FlagLifecycle;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.Strategy;
import com.featureflag.flags.StrategyType;
import com.featureflag.flags.UserContext;
import com.featureflag.flags.Variation;

/** Precedence and behavior of individual targets and targeting rules inside {@link FlagEvaluationService}. */
class TargetingEvaluationTest {

	private static final EnvironmentKey ENV = EnvironmentKey.PRODUCTION;

	private final Map<String, Segment> segments = new HashMap<>();

	private final Map<String, MobileApplication> applications = new HashMap<>();

	private final FlagEvaluationService evaluationService = new FlagEvaluationService(new TargetingLookup() {
		@Override
		public Optional<Segment> segment(EnvironmentKey environment, String segmentKey) {
			return environment == ENV ? Optional.ofNullable(segments.get(segmentKey)) : Optional.empty();
		}

		@Override
		public Optional<MobileApplication> application(String applicationKey) {
			return Optional.ofNullable(applications.get(applicationKey));
		}
	});

	// ---- precedence

	@Test
	void individualTargetBeatsEveryRuleAndStrategy() {
		EnvironmentConfig config = config(true);
		config.getIndividualTargets().add(new IndividualTarget("user", "off", Set.of("vip")));
		config.getRules().add(rule("everyone-on", "on", clause("key", ClauseOperator.STARTS_WITH, "v")));
		config.getStrategies().add(rollout("on", 100));

		EvaluationResult result = evaluate(flag(config), UserContext.anonymous("vip"));

		assertThat(result.variationKey()).isEqualTo("off");
		assertThat(result.reason()).isEqualTo(EvaluationReason.INDIVIDUAL_TARGET);
		assertThat(result.matchedRuleId()).isNull();
	}

	@Test
	void rulesBeatLegacyStrategiesAndRollouts() {
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("pro-off", "off", clause("plan", ClauseOperator.IN, "pro")));
		config.getStrategies().add(rollout("on", 100));

		EvaluationResult result = evaluate(flag(config), new UserContext("u1", Map.of("plan", "pro")));

		assertThat(result.variationKey()).isEqualTo("off");
		assertThat(result.reason()).isEqualTo(EvaluationReason.RULE_MATCH);
		assertThat(result.matchedRuleId()).isEqualTo("pro-off");
		assertThat(result.matchedStrategyId()).isNull();
	}

	@Test
	void whenNoRuleMatchesEvaluationFallsThroughToLegacyStrategiesUnchanged() {
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("pro-off", "off", clause("plan", ClauseOperator.IN, "pro")));
		config.getStrategies().add(rollout("on", 100));

		EvaluationResult result = evaluate(flag(config), new UserContext("u1", Map.of("plan", "free")));

		assertThat(result.variationKey()).isEqualTo("on");
		assertThat(result.reason()).isEqualTo(EvaluationReason.ROLLOUT_ENABLED);
	}

	@Test
	void nothingMatchingServesTheOffVariationAsFallthrough() {
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("pro-on", "on", clause("plan", ClauseOperator.IN, "pro")));

		EvaluationResult result = evaluate(flag(config), new UserContext("u1", Map.of("plan", "free")));

		assertThat(result.variationKey()).isEqualTo("off");
		assertThat(result.reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
	}

	@Test
	void aDisabledEnvironmentShortCircuitsTargetingEntirely() {
		EnvironmentConfig config = config(false);
		config.getIndividualTargets().add(new IndividualTarget("user", "on", Set.of("vip")));
		config.getRules().add(rule("all", "on", clause("key", ClauseOperator.STARTS_WITH, "")));

		EvaluationResult result = evaluate(flag(config), UserContext.anonymous("vip"));

		assertThat(result.reason()).isEqualTo(EvaluationReason.FLAG_DISABLED);
		assertThat(result.variationKey()).isEqualTo("off");
	}

	@Test
	void environmentsWithoutRulesOrTargetsEvaluateExactlyAsBefore() {
		EnvironmentConfig config = config(true);
		config.getStrategies().add(rollout("on", 100));

		EvaluationResult result = evaluate(flag(config), UserContext.anonymous("anyone"));

		assertThat(result.reason()).isEqualTo(EvaluationReason.ROLLOUT_ENABLED);
	}

	// ---- rule ordering and enabling

	@Test
	void theFirstMatchingRuleInListOrderWins() {
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("first", "off", clause("plan", ClauseOperator.IN, "pro")));
		config.getRules().add(rule("second", "on", clause("plan", ClauseOperator.IN, "pro")));

		assertThat(evaluate(flag(config), new UserContext("u1", Map.of("plan", "pro"))).matchedRuleId()).isEqualTo("first");

		java.util.Collections.reverse(config.getRules());

		assertThat(evaluate(flag(config), new UserContext("u1", Map.of("plan", "pro"))).matchedRuleId())
				.as("reordering changes who wins").isEqualTo("second");
	}

	@Test
	void aDisabledRuleIsSkippedAndItsBackupIsUsedUntilItIsEnabledAgain() {
		TargetingRule primary = rule("primary", "on", clause("plan", ClauseOperator.IN, "pro"));
		TargetingRule backup = rule("backup", "off", clause("plan", ClauseOperator.IN, "pro"));
		EnvironmentConfig config = config(true);
		config.getRules().add(primary);
		config.getRules().add(backup);
		UserContext pro = new UserContext("u1", Map.of("plan", "pro"));

		primary.setEnabled(false);

		assertThat(evaluate(flag(config), pro).matchedRuleId()).isEqualTo("backup");
		assertThat(config.getRules()).as("disabling never deletes").hasSize(2);

		primary.setEnabled(true);

		assertThat(evaluate(flag(config), pro).matchedRuleId()).isEqualTo("primary");
	}

	@Test
	void allDisabledRulesFallThrough() {
		TargetingRule only = rule("only", "on", clause("plan", ClauseOperator.IN, "pro"));
		only.setEnabled(false);
		EnvironmentConfig config = config(true);
		config.getRules().add(only);

		assertThat(evaluate(flag(config), new UserContext("u1", Map.of("plan", "pro"))).reason())
				.isEqualTo(EvaluationReason.FALLTHROUGH);
	}

	@Test
	void aRuleWithNoClausesNeverMatches() {
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("empty", "on"));

		assertThat(evaluate(flag(config), UserContext.anonymous("u1")).reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
	}

	@Test
	void allOfARulesClausesMustMatch() {
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("both", "on", clause("plan", ClauseOperator.IN, "pro"), clause("country", ClauseOperator.IN, "US")));

		assertThat(evaluate(flag(config), new UserContext("u1", Map.of("plan", "pro", "country", "US"))).reason())
				.isEqualTo(EvaluationReason.RULE_MATCH);
		assertThat(evaluate(flag(config), new UserContext("u2", Map.of("plan", "pro", "country", "CA"))).reason())
				.isEqualTo(EvaluationReason.FALLTHROUGH);
	}

	// ---- individual targets

	@Test
	void individualTargetsApplyPerContextKind() {
		EnvironmentConfig config = config(true);
		config.getIndividualTargets().add(new IndividualTarget("organization", "on", Set.of("org-7")));
		UserContext inOrg = new UserContext("someone-else", Map.of(), Map.of("organization", new ContextEntry("org-7", Map.of())));
		UserContext userNamedLikeOrg = UserContext.anonymous("org-7");

		assertThat(evaluate(flag(config), inOrg).reason()).isEqualTo(EvaluationReason.INDIVIDUAL_TARGET);
		assertThat(evaluate(flag(config), userNamedLikeOrg).reason()).as("an org key must not match a user key").isEqualTo(EvaluationReason.FALLTHROUGH);
	}

	@Test
	void anAnonymousUserNeverMatchesAnIndividualTarget() {
		EnvironmentConfig config = config(true);
		config.getIndividualTargets().add(new IndividualTarget("user", "on", Set.of("vip")));

		assertThat(evaluate(flag(config), UserContext.anonymous(null)).reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
	}

	@Test
	void individualTargetingStaysConstantTimeForLargeBulkLists() {
		Set<String> keys = new LinkedHashSet<>();

		for (int index = 0; index < 50_000; index++) {
			keys.add("user-" + index);
		}

		EnvironmentConfig config = config(true);
		config.getIndividualTargets().add(new IndividualTarget("user", "on", keys));
		Flag flag = flag(config);
		long started = System.nanoTime();

		for (int index = 0; index < 1_000; index++) {
			assertThat(evaluate(flag, UserContext.anonymous("user-49999")).reason()).isEqualTo(EvaluationReason.INDIVIDUAL_TARGET);
		}

		assertThat((System.nanoTime() - started) / 1_000_000).as("1000 evaluations against 50k keys, ms").isLessThan(1_000);
	}

	// ---- rule rollouts

	@Test
	void aRuleCanServeAPercentageRolloutInsteadOfAFixedVariation() {
		TargetingRule rule = rule("half", null, clause("plan", ClauseOperator.IN, "pro"));
		rule.setRolloutWeights(new LinkedHashMap<>(Map.of("off", 50, "on", 50)));
		EnvironmentConfig config = config(true);
		config.getRules().add(rule);
		Flag flag = flag(config);
		int on = 0;

		for (int index = 0; index < 2_000; index++) {
			EvaluationResult result = evaluate(flag, new UserContext("user-" + index, Map.of("plan", "pro")));

			assertThat(result.reason()).isEqualTo(EvaluationReason.RULE_MATCH);
			assertThat(result.matchedRuleId()).isEqualTo("half");

			if (result.variationKey().equals("on")) {
				on++;
			}
		}

		assertThat(on).as("about half of 2000 users").isBetween(800, 1_200);
	}

	@Test
	void aRuleRolloutIsStickyPerUser() {
		TargetingRule rule = rule("half", null, clause("plan", ClauseOperator.IN, "pro"));
		rule.setRolloutWeights(new LinkedHashMap<>(Map.of("off", 50, "on", 50)));
		EnvironmentConfig config = config(true);
		config.getRules().add(rule);
		Flag flag = flag(config);

		for (int index = 0; index < 200; index++) {
			UserContext user = new UserContext("user-" + index, Map.of("plan", "pro"));
			String first = evaluate(flag, user).variationKey();

			for (int repeat = 0; repeat < 5; repeat++) {
				assertThat(evaluate(flag, user).variationKey()).isEqualTo(first);
			}
		}
	}

	@Test
	void aHundredPercentRolloutOnOneVariationAlwaysServesIt() {
		TargetingRule rule = rule("all-on", null, clause("plan", ClauseOperator.IN, "pro"));
		rule.setRolloutWeights(new LinkedHashMap<>(Map.of("off", 0, "on", 100)));
		EnvironmentConfig config = config(true);
		config.getRules().add(rule);
		Flag flag = flag(config);

		for (int index = 0; index < 100; index++) {
			assertThat(evaluate(flag, new UserContext("user-" + index, Map.of("plan", "pro"))).variationKey()).isEqualTo("on");
		}
	}

	// ---- segments

	@Test
	void aSegmentRuleServesItsVariationToMembersOnly() {
		segments.put("beta-testers", segment("beta-testers", "user", Set.of("tester-1"), Set.of()));
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("beta", "on", new Clause("user", null, ClauseOperator.IN_SEGMENT, List.of("beta-testers"), false)));

		assertThat(evaluate(flag(config), UserContext.anonymous("tester-1")).matchedRuleId()).isEqualTo("beta");
		assertThat(evaluate(flag(config), UserContext.anonymous("outsider")).reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
	}

	@Test
	void aNotInSegmentRuleServesEveryoneOutsideTheSegment() {
		segments.put("beta-testers", segment("beta-testers", "user", Set.of("tester-1"), Set.of()));
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("not-beta", "off", new Clause("user", null, ClauseOperator.IN_SEGMENT, List.of("beta-testers"), true)));
		config.setOffVariationKey("on");

		assertThat(evaluate(flag(config), UserContext.anonymous("outsider")).variationKey()).isEqualTo("off");
		assertThat(evaluate(flag(config), UserContext.anonymous("tester-1")).variationKey()).isEqualTo("on");
	}

	@Test
	void changingASegmentChangesEvaluationImmediatelyWithoutTouchingTheFlag() {
		Segment beta = segment("beta-testers", "user", Set.of(), Set.of());
		segments.put("beta-testers", beta);
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("beta", "on", new Clause("user", null, ClauseOperator.IN_SEGMENT, List.of("beta-testers"), false)));
		Flag flag = flag(config);

		assertThat(evaluate(flag, UserContext.anonymous("late-joiner")).reason()).isEqualTo(EvaluationReason.FALLTHROUGH);

		beta.getIncluded().add("late-joiner");

		assertThat(evaluate(flag, UserContext.anonymous("late-joiner")).reason()).isEqualTo(EvaluationReason.RULE_MATCH);
	}

	@Test
	void aRuleCanCombineASegmentWithAnAttributeCondition() {
		segments.put("beta-testers", segment("beta-testers", "user", Set.of("tester-1", "tester-2"), Set.of()));
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("beta-pro", "on", new Clause("user", null, ClauseOperator.IN_SEGMENT, List.of("beta-testers"), false),
				clause("plan", ClauseOperator.IN, "pro")));

		assertThat(evaluate(flag(config), new UserContext("tester-1", Map.of("plan", "pro"))).reason()).isEqualTo(EvaluationReason.RULE_MATCH);
		assertThat(evaluate(flag(config), new UserContext("tester-2", Map.of("plan", "free"))).reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
		assertThat(evaluate(flag(config), new UserContext("outsider", Map.of("plan", "pro"))).reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
	}

	@Test
	void withNoSegmentStoreSegmentRulesSimplyDontMatch() {
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("beta", "on", new Clause("user", null, ClauseOperator.IN_SEGMENT, List.of("beta-testers"), false)));

		EvaluationResult result = new FlagEvaluationService().evaluate(flag(config), ENV, UserContext.anonymous("tester-1"));

		assertThat(result.reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
	}

	// ---- multiple context kinds

	@Test
	void oneRuleCanCombineConditionsAcrossContextKinds() {
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("pro-user-at-beta-org", "on", clause("plan", ClauseOperator.IN, "pro"),
				new Clause("organization", "key", ClauseOperator.IN, List.of("beta"), false)));
		Map<String, ContextEntry> betaOrg = Map.of("organization", new ContextEntry("beta", Map.of()));
		Map<String, ContextEntry> otherOrg = Map.of("organization", new ContextEntry("other", Map.of()));

		assertThat(evaluate(flag(config), new UserContext("u1", Map.of("plan", "pro"), betaOrg)).reason()).isEqualTo(EvaluationReason.RULE_MATCH);
		assertThat(evaluate(flag(config), new UserContext("u1", Map.of("plan", "pro"), otherOrg)).reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
		assertThat(evaluate(flag(config), new UserContext("u1", Map.of("plan", "free"), betaOrg)).reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
		assertThat(evaluate(flag(config), new UserContext("u1", Map.of("plan", "pro"))).reason()).as("no organization supplied").isEqualTo(EvaluationReason.FALLTHROUGH);
	}

	// ---- mobile targeting

	@Test
	void mobileRuleTargetsAppVersionAndDeviceAttributes() {
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("new-android", "on",
				new Clause("ld_application", "versionName", ClauseOperator.SEMVER_GREATER_THAN, List.of("3.0.0"), false),
				new Clause("ld_device", "os.name", ClauseOperator.IN, List.of("Android"), false)));

		UserContext modernAndroid = mobile("com.acme.app", "3.1.0", "Android");
		UserContext oldAndroid = mobile("com.acme.app", "2.9.0", "Android");
		UserContext modernIos = mobile("com.acme.app", "3.1.0", "iOS");

		assertThat(evaluate(flag(config), modernAndroid).matchedRuleId()).isEqualTo("new-android");
		assertThat(evaluate(flag(config), oldAndroid).reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
		assertThat(evaluate(flag(config), modernIos).reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
	}

	@Test
	void unsupportedAppVersionsCanBeTargetedToServeAnUpgradePrompt() {
		applications.put("com.acme.app", application("com.acme.app", "2.4.0"));
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("force-upgrade", "on",
				new Clause("ld_application", null, ClauseOperator.APP_VERSION_SUPPORTED, List.of(), true)));

		assertThat(evaluate(flag(config), mobile("com.acme.app", "2.3.0", "Android")).matchedRuleId()).isEqualTo("force-upgrade");
		assertThat(evaluate(flag(config), mobile("com.acme.app", "2.4.0", "Android")).reason()).isEqualTo(EvaluationReason.FALLTHROUGH);
	}

	@Test
	void raisingTheMinimumSupportedVersionTakesEffectWithoutEditingTheFlag() {
		MobileApplication app = application("com.acme.app", "2.0.0");
		applications.put("com.acme.app", app);
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("force-upgrade", "on",
				new Clause("ld_application", null, ClauseOperator.APP_VERSION_SUPPORTED, List.of(), true)));
		Flag flag = flag(config);
		UserContext v230 = mobile("com.acme.app", "2.3.0", "Android");

		assertThat(evaluate(flag, v230).reason()).isEqualTo(EvaluationReason.FALLTHROUGH);

		app.setMinSupportedVersion("2.4.0");

		assertThat(evaluate(flag, v230).matchedRuleId()).isEqualTo("force-upgrade");
	}

	// ---- experiment inclusion

	@Test
	void individualTargetsAreNeverPartOfAnExperiment() {
		EnvironmentConfig config = config(true);
		config.getIndividualTargets().add(new IndividualTarget("user", "on", Set.of("vip")));

		assertThat(evaluate(flag(config), UserContext.anonymous("vip")).includedInExperiment()).isFalse();
	}

	@Test
	void aFixedVariationRuleIsExcludedFromTheExperiment() {
		EnvironmentConfig config = config(true);
		config.getRules().add(rule("fixed", "on", clause("plan", ClauseOperator.IN, "pro")));

		EvaluationResult result = evaluate(flag(config), new UserContext("u1", Map.of("plan", "pro")));

		assertThat(result.reason()).isEqualTo(EvaluationReason.RULE_MATCH);
		assertThat(result.includedInExperiment()).isFalse();
	}

	@Test
	void aRolloutRuleIsExcludedUnlessItOptsInAndIncludedWhenItDoes() {
		TargetingRule optedOut = rule("out", null, clause("plan", ClauseOperator.IN, "trial"));
		optedOut.setRolloutWeights(new LinkedHashMap<>(Map.of("off", 50, "on", 50)));
		TargetingRule optedIn = rule("in", null, clause("plan", ClauseOperator.IN, "beta"));
		optedIn.setRolloutWeights(new LinkedHashMap<>(Map.of("off", 50, "on", 50)));
		optedIn.setIncludeInExperiment(true);
		EnvironmentConfig config = config(true);
		config.getRules().add(optedOut);
		config.getRules().add(optedIn);
		Flag flag = flag(config);

		assertThat(evaluate(flag, new UserContext("u1", Map.of("plan", "trial"))).includedInExperiment()).isFalse();
		assertThat(evaluate(flag, new UserContext("u2", Map.of("plan", "beta"))).includedInExperiment()).isTrue();
	}

	@Test
	void theFlagOnAFixedRuleIsIgnoredEvenIfStaleDataHasItSet() {
		TargetingRule stale = rule("stale", "on", clause("plan", ClauseOperator.IN, "pro"));
		stale.setIncludeInExperiment(true);
		EnvironmentConfig config = config(true);
		config.getRules().add(stale);

		assertThat(evaluate(flag(config), new UserContext("u1", Map.of("plan", "pro"))).includedInExperiment()).isFalse();
	}

	@Test
	void everythingElseStillCountsTowardTheExperimentExactlyAsBefore() {
		EnvironmentConfig config = config(true);
		Strategy split = new Strategy();
		split.setId("split");
		split.setType(StrategyType.EXPERIMENT_SPLIT);
		split.setVariationWeights(new LinkedHashMap<>(Map.of("off", 50, "on", 50)));
		config.getStrategies().add(split);
		config.getRules().add(rule("fixed", "on", clause("plan", ClauseOperator.IN, "pro")));
		Flag flag = flag(config);

		EvaluationResult assigned = evaluate(flag, new UserContext("u1", Map.of("plan", "free")));
		assertThat(assigned.reason()).isEqualTo(EvaluationReason.EXPERIMENT_ASSIGNED);
		assertThat(assigned.includedInExperiment()).isTrue();

		assertThat(evaluate(flag(config(false)), UserContext.anonymous("u1")).includedInExperiment()).as("disabled").isTrue();
		assertThat(evaluate(flag(config(true)), UserContext.anonymous("u1")).includedInExperiment()).as("fallthrough").isTrue();
	}

	// ---- helpers

	private EvaluationResult evaluate(Flag flag, UserContext context) {
		return evaluationService.evaluate(flag, ENV, context);
	}

	private static UserContext mobile(String appKey, String versionName, String osName) {
		return new UserContext("u1", Map.of(), Map.of(
				"ld_application", new ContextEntry(appKey, Map.of("versionName", versionName)),
				"ld_device", new ContextEntry("device-1", Map.of("os.name", osName))));
	}

	private static Clause clause(String attribute, ClauseOperator operator, String... values) {
		return new Clause("user", attribute, operator, List.of(values), false);
	}

	private static TargetingRule rule(String id, String servedVariationKey, Clause... clauses) {
		TargetingRule rule = new TargetingRule();
		rule.setId(id);
		rule.setServedVariationKey(servedVariationKey);
		rule.setClauses(new ArrayList<>(List.of(clauses)));

		return rule;
	}

	private static Strategy rollout(String servedVariationKey, int percentage) {
		Strategy strategy = new Strategy();
		strategy.setId("strategy-rollout");
		strategy.setType(StrategyType.PERCENTAGE_ROLLOUT);
		strategy.setPercentage(percentage);
		strategy.setServedVariationKey(servedVariationKey);

		return strategy;
	}

	private static EnvironmentConfig config(boolean enabled) {
		return new EnvironmentConfig(enabled, "off", new ArrayList<>());
	}

	private static Flag flag(EnvironmentConfig config) {
		Flag flag = new Flag();
		flag.setKey("targeted-flag");
		flag.setName("Targeted Flag");
		flag.setType(FlagType.BOOLEAN);
		flag.setLifecycle(FlagLifecycle.ACTIVE);
		flag.setVariations(new ArrayList<>(List.of(new Variation("off", "Off", "false"), new Variation("on", "On", "true"))));

		Map<EnvironmentKey, EnvironmentConfig> environments = new EnumMap<>(EnvironmentKey.class);
		environments.put(ENV, config);
		flag.setEnvironments(environments);

		return flag;
	}

	private static Segment segment(String key, String contextKind, Set<String> included, Set<String> excluded) {
		Segment segment = new Segment();
		segment.setEnvironment(ENV);
		segment.setKey(key);
		segment.setName(key);
		segment.setContextKind(contextKind);
		segment.setIncluded(new LinkedHashSet<>(included));
		segment.setExcluded(new LinkedHashSet<>(excluded));

		return segment;
	}

	private static MobileApplication application(String key, String minSupportedVersion) {
		MobileApplication application = new MobileApplication();
		application.setKey(key);
		application.setName(key);
		application.setMinSupportedVersion(minSupportedVersion);

		return application;
	}
}
