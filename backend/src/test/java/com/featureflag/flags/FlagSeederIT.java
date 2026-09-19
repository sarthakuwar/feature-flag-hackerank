package com.featureflag.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.metrics.ConversionRepository;
import com.featureflag.flags.metrics.ImpressionRepository;
import com.featureflag.flags.targeting.MobileApplicationRepository;
import com.featureflag.flags.targeting.RuleCategory;
import com.featureflag.flags.targeting.Segment;
import com.featureflag.flags.targeting.SegmentRepository;
import com.featureflag.flags.targeting.TargetingRule;
import com.featureflag.shared.ApiException;

/**
 * Boots with a real (non-"test") profile so {@link FlagSeeder}, which is disabled under the
 * "test" profile for the other integration tests, actually runs — against its own isolated
 * database (application-seed-test.properties), never the dev or shared-test databases.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@ActiveProfiles("seed-test")
class FlagSeederIT {

	@Autowired
	private FlagSeeder flagSeeder;

	@Autowired
	private FlagRepository flagRepository;

	@Autowired
	private AuditRepository auditRepository;

	@Autowired
	private FlagService flagService;

	@Autowired
	private ImpressionRepository impressionRepository;

	@Autowired
	private ConversionRepository conversionRepository;

	@Autowired
	private SegmentRepository segmentRepository;

	@Autowired
	private MobileApplicationRepository mobileApplicationRepository;

	@Autowired
	private FlagCacheService flagCacheService;

	@Autowired
	private FlagEvaluationService evaluationService;

	@Test
	void contextStartupSeedsARealisticBaseline() {
		List<Flag> flags = flagRepository.findAll();

		assertThat(flags).hasSize(6);
		Set<String> keys = flags.stream().map(Flag::getKey).collect(java.util.stream.Collectors.toSet());
		assertThat(keys).containsExactlyInAnyOrder("new-checkout-flow", "checkout-analytics-dashboard",
				"pricing-page-redesign", "beta-dashboard-access", "dark-mode-default", "experimental-ai-summaries");

		Flag archived = flagRepository.findByKey("beta-dashboard-access").orElseThrow();
		assertThat(archived.getLifecycle()).isEqualTo(FlagLifecycle.ARCHIVED);

		Flag rollout = flagRepository.findByKey("new-checkout-flow").orElseThrow();
		Strategy productionRollout = rollout.getEnvironments().get(EnvironmentKey.PRODUCTION).getStrategies().get(0);
		assertThat(productionRollout.getType()).isEqualTo(StrategyType.PERCENTAGE_ROLLOUT);
		assertThat(productionRollout.getPercentage()).isEqualTo(25);

		Flag dependent = flagRepository.findByKey("checkout-analytics-dashboard").orElseThrow();
		assertThat(dependent.getDependsOn()).containsExactly("new-checkout-flow");
	}

	@Test
	void pricingPageRedesignIsSeededWithWeightsAPayloadAndAnEpsilonGreedyBanditInProduction() {
		Flag flag = flagRepository.findByKey("pricing-page-redesign").orElseThrow();

		assertThat(flag.getVariations()).extracting(Variation::getWeight).containsExactly(34, 33, 33);
		Variation newHero = flag.getVariations().stream().filter(v -> v.getKey().equals("new-hero")).findFirst().orElseThrow();
		assertThat(newHero.getPayloadType()).isEqualTo(PayloadType.JSON);
		assertThat(newHero.getPayloadValue()).contains("heroImage");

		Strategy experimentStrategy = flag.getEnvironments().get(EnvironmentKey.PRODUCTION).getStrategies().stream()
				.filter(strategy -> strategy.getType() == StrategyType.EXPERIMENT_SPLIT).findFirst().orElseThrow();
		assertThat(experimentStrategy.getBanditAlgorithm()).isEqualTo(BanditAlgorithm.EPSILON_GREEDY);
		assertThat(experimentStrategy.getVariationWeights().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);

		assertThat(impressionRepository.findByFlagKeyAndEnvironment("pricing-page-redesign", EnvironmentKey.PRODUCTION))
				.isNotEmpty();
		assertThat(conversionRepository.findByFlagKeyAndEnvironment("pricing-page-redesign", EnvironmentKey.PRODUCTION))
				.isNotEmpty();
	}

	@Test
	void experimentalAiSummariesIsSeededWithAThompsonSamplingBanditInStaging() {
		Flag flag = flagRepository.findByKey("experimental-ai-summaries").orElseThrow();

		Strategy experimentStrategy = flag.getEnvironments().get(EnvironmentKey.STAGING).getStrategies().stream()
				.filter(strategy -> strategy.getType() == StrategyType.EXPERIMENT_SPLIT).findFirst().orElseThrow();
		assertThat(experimentStrategy.getBanditAlgorithm()).isEqualTo(BanditAlgorithm.THOMPSON_SAMPLING);
		assertThat(experimentStrategy.getVariationWeights().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);

		assertThat(impressionRepository.findByFlagKeyAndEnvironment("experimental-ai-summaries", EnvironmentKey.STAGING))
				.isNotEmpty();
	}

	@Test
	void seededDependencyBlocksArchivingItsPrerequisite() {
		assertThatThrownBy(() -> flagService.archive("new-checkout-flow", "tester")).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("code", "FLAG_HAS_DEPENDENTS");

		Flag stillActive = flagRepository.findByKey("new-checkout-flow").orElseThrow();
		assertThat(stillActive.getLifecycle()).isEqualTo(FlagLifecycle.ACTIVE);
	}

	@Test
	void segmentsAreSeededPerEnvironmentWithMembersRulesExclusionsAndANonUserKind() {
		List<Segment> production = segmentRepository.findByEnvironment(EnvironmentKey.PRODUCTION);
		List<Segment> staging = segmentRepository.findByEnvironment(EnvironmentKey.STAGING);

		assertThat(production).extracting(Segment::getKey)
				.containsExactlyInAnyOrder("beta-testers", "enterprise-customers", "mobile-beta", "internal-orgs");
		assertThat(staging).extracting(Segment::getKey).containsExactly("beta-testers");
		assertThat(segmentRepository.findByEnvironment(EnvironmentKey.DEVELOPMENT)).isEmpty();

		Segment prodBeta = segmentRepository.findByEnvironmentAndKey(EnvironmentKey.PRODUCTION, "beta-testers").orElseThrow();
		Segment stagingBeta = segmentRepository.findByEnvironmentAndKey(EnvironmentKey.STAGING, "beta-testers").orElseThrow();
		assertThat(prodBeta.getIncluded()).containsExactly("user-1001", "user-1002", "user-1003");
		assertThat(stagingBeta.getIncluded()).as("same key, different audience per environment").isEmpty();

		Segment enterprise = segmentRepository.findByEnvironmentAndKey(EnvironmentKey.PRODUCTION, "enterprise-customers").orElseThrow();
		assertThat(enterprise.getExcluded()).containsExactly("user-9999");
		assertThat(enterprise.getRules()).hasSize(1);

		Segment orgs = segmentRepository.findByEnvironmentAndKey(EnvironmentKey.PRODUCTION, "internal-orgs").orElseThrow();
		assertThat(orgs.getContextKind()).isEqualTo("organization");
	}

	@Test
	void mobileApplicationsAreSeeded() {
		assertThat(mobileApplicationRepository.findAll()).extracting(app -> app.getKey())
				.containsExactlyInAnyOrder("com.featureflag.mobile", "com.featureflag.tablet");
	}

	@Test
	void targetingRulesAndIndividualTargetsAreSeededAcrossEveryCategory() {
		List<TargetingRule> checkout = flagRepository.findByKey("new-checkout-flow").orElseThrow().getEnvironments()
				.get(EnvironmentKey.PRODUCTION).getRules();
		assertThat(checkout).hasSize(2);
		assertThat(checkout.get(0).category()).isEqualTo(RuleCategory.SEGMENT);
		assertThat(checkout.get(1).isEnabled()).as("a seeded, paused rule shows disable-without-delete").isFalse();

		List<TargetingRule> darkMode = flagRepository.findByKey("dark-mode-default").orElseThrow().getEnvironments()
				.get(EnvironmentKey.DEVELOPMENT).getRules();
		assertThat(darkMode).extracting(TargetingRule::category).containsExactly(RuleCategory.MOBILE, RuleCategory.MOBILE);

		List<TargetingRule> pricing = flagRepository.findByKey("pricing-page-redesign").orElseThrow().getEnvironments()
				.get(EnvironmentKey.PRODUCTION).getRules();
		assertThat(pricing.get(0).getClauses().get(0).getContextKind()).isEqualTo("organization");

		EnvironmentConfig aiStaging = flagRepository.findByKey("experimental-ai-summaries").orElseThrow().getEnvironments()
				.get(EnvironmentKey.STAGING);
		assertThat(aiStaging.getIndividualTargets()).hasSize(2);
	}

	@Test
	void seededTargetingActuallyDrivesEvaluationEndToEnd() {
		Flag checkout = flagCacheService.get("new-checkout-flow").orElseThrow();
		Flag darkMode = flagCacheService.get("dark-mode-default").orElseThrow();
		Flag aiSummaries = flagCacheService.get("experimental-ai-summaries").orElseThrow();

		EvaluationResult betaTester = evaluationService.evaluate(checkout, EnvironmentKey.PRODUCTION, UserContext.anonymous("user-1001"));
		assertThat(betaTester.reason()).isEqualTo(EvaluationReason.RULE_MATCH);
		assertThat(betaTester.variationKey()).isEqualTo("on");

		EvaluationResult staff = evaluationService.evaluate(checkout, EnvironmentKey.PRODUCTION,
				new UserContext("someone", java.util.Map.of("email", "sam@featureflag.dev")));
		assertThat(staff.reason()).as("segment rule membership by email").isEqualTo(EvaluationReason.RULE_MATCH);

		EvaluationResult pausedRuleIgnored = evaluationService.evaluate(checkout, EnvironmentKey.PRODUCTION,
				new UserContext("berlin-user", java.util.Map.of("country", "DE")));
		assertThat(pausedRuleIgnored.matchedRuleId()).as("the disabled Germany rule must not fire").isNull();

		UserContext oldApp = new UserContext("u1", java.util.Map.of(), java.util.Map.of(
				"ld_application", new ContextEntry("com.featureflag.mobile", java.util.Map.of("versionName", "2.3.0"))));
		UserContext currentAndroid = new UserContext("u1", java.util.Map.of(), java.util.Map.of(
				"ld_application", new ContextEntry("com.featureflag.mobile", java.util.Map.of("versionName", "2.5.0")),
				"ld_device", new ContextEntry("d1", java.util.Map.of("os.name", "Android"))));
		assertThat(evaluationService.evaluate(darkMode, EnvironmentKey.DEVELOPMENT, oldApp).variationKey()).isEqualTo("off");
		assertThat(evaluationService.evaluate(darkMode, EnvironmentKey.DEVELOPMENT, currentAndroid).variationKey()).isEqualTo("on");

		EvaluationResult targetedOverExperiment = evaluationService.evaluate(aiSummaries, EnvironmentKey.STAGING,
				UserContext.anonymous("qa-lead"));
		assertThat(targetedOverExperiment.reason()).isEqualTo(EvaluationReason.INDIVIDUAL_TARGET);
		assertThat(targetedOverExperiment.variationKey()).isEqualTo("concise");
	}

	@Test
	void runningTheSeederAgainResetsToTheSameBaselineWithoutDuplicating() {
		long segmentCountBefore = segmentRepository.count();
		long applicationCountBefore = mobileApplicationRepository.count();
		long flagCountBefore = flagRepository.count();
		long auditCountBefore = auditRepository.count();
		long impressionCountBefore = impressionRepository.count();
		long conversionCountBefore = conversionRepository.count();

		flagSeeder.run(new DefaultApplicationArguments());

		assertThat(flagRepository.count()).isEqualTo(flagCountBefore);
		assertThat(auditRepository.count()).isEqualTo(auditCountBefore);
		assertThat(impressionRepository.count()).isEqualTo(impressionCountBefore);
		assertThat(conversionRepository.count()).isEqualTo(conversionCountBefore);
		assertThat(segmentRepository.count()).isEqualTo(segmentCountBefore);
		assertThat(mobileApplicationRepository.count()).isEqualTo(applicationCountBefore);
	}
}
