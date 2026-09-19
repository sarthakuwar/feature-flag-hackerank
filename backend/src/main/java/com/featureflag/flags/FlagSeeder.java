package com.featureflag.flags;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.StrategyRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.experiment.ExperimentService;
import com.featureflag.flags.metrics.ConversionEvent;
import com.featureflag.flags.metrics.ConversionRepository;
import com.featureflag.flags.metrics.ImpressionEvent;
import com.featureflag.flags.metrics.ImpressionRepository;
import com.featureflag.flags.rollout.ProgressiveRolloutService;
import com.featureflag.flags.rollout.dto.StartProgressiveRolloutRequest;
import com.featureflag.flags.targeting.BulkOperation;
import com.featureflag.flags.targeting.ClauseOperator;
import com.featureflag.flags.targeting.MobileApplicationRepository;
import com.featureflag.flags.targeting.MobileApplicationService;
import com.featureflag.flags.targeting.SegmentRepository;
import com.featureflag.flags.targeting.SegmentService;
import com.featureflag.flags.targeting.TargetingCacheService;
import com.featureflag.flags.targeting.TargetingRule;
import com.featureflag.flags.targeting.TargetingService;
import com.featureflag.flags.targeting.dto.BulkTargetRequest;
import com.featureflag.flags.targeting.dto.ClauseRequest;
import com.featureflag.flags.targeting.dto.MobileApplicationRequest;
import com.featureflag.flags.targeting.dto.SegmentCreateRequest;
import com.featureflag.flags.targeting.dto.SegmentRuleRequest;
import com.featureflag.flags.targeting.dto.TargetingRuleRequest;

/**
 * Seeds demo data in code on every backend start: clear the flag collections, then insert a
 * realistic baseline, per GUIDELINES.md 3.4 ("seed logic clears application collections before
 * inserting the baseline"). There is no Gradle seed task; setup.sh only builds, and this runs when
 * the backend starts. Skipped for the "test" profile so integration tests own their own fixtures,
 * and skipped (with a warning, touching nothing) when {@link SeedGuard} says the configured MongoDB
 * is not on this machine and FEATUREFLAG_SEED=true does not force it.
 */
@Component
@Profile("!test")
@Order(1)
public class FlagSeeder implements ApplicationRunner {

	private static final Logger LOG = LoggerFactory.getLogger(FlagSeeder.class);

	private final SeedGuard seedGuard;

	private final FlagRepository flagRepository;

	private final AuditRepository auditRepository;

	private final FlagService flagService;

	private final FlagCacheService flagCacheService;

	private final ImpressionRepository impressionRepository;

	private final ConversionRepository conversionRepository;

	private final ExperimentService experimentService;

	private final SegmentRepository segmentRepository;

	private final MobileApplicationRepository mobileApplicationRepository;

	private final SegmentService segmentService;

	private final MobileApplicationService mobileApplicationService;

	private final TargetingService targetingService;

	private final TargetingCacheService targetingCacheService;

	private final ProgressiveRolloutService progressiveRolloutService;

	public FlagSeeder(SeedGuard seedGuard, FlagRepository flagRepository, AuditRepository auditRepository, FlagService flagService,
			FlagCacheService flagCacheService, ImpressionRepository impressionRepository,
			ConversionRepository conversionRepository, ExperimentService experimentService,
			SegmentRepository segmentRepository, MobileApplicationRepository mobileApplicationRepository,
			SegmentService segmentService, MobileApplicationService mobileApplicationService,
			TargetingService targetingService, TargetingCacheService targetingCacheService,
			ProgressiveRolloutService progressiveRolloutService) {
		this.seedGuard = seedGuard;
		this.flagRepository = flagRepository;
		this.auditRepository = auditRepository;
		this.flagService = flagService;
		this.flagCacheService = flagCacheService;
		this.impressionRepository = impressionRepository;
		this.conversionRepository = conversionRepository;
		this.experimentService = experimentService;
		this.segmentRepository = segmentRepository;
		this.mobileApplicationRepository = mobileApplicationRepository;
		this.segmentService = segmentService;
		this.mobileApplicationService = mobileApplicationService;
		this.targetingService = targetingService;
		this.targetingCacheService = targetingCacheService;
		this.progressiveRolloutService = progressiveRolloutService;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!seedGuard.permitsSeeding()) {
			LOG.warn("Not seeding: the configured MongoDB is not on this machine, and seeding would delete its flags, audit"
					+ " history, metrics, segments and applications. Set FEATUREFLAG_SEED=true to seed it anyway.");

			return;
		}

		if (seedGuard.isForced()) {
			LOG.warn("FEATUREFLAG_SEED=true: clearing and reseeding the configured MongoDB.");
		}

		flagRepository.deleteAll();
		auditRepository.deleteAll();
		impressionRepository.deleteAll();
		conversionRepository.deleteAll();
		segmentRepository.deleteAll();
		mobileApplicationRepository.deleteAll();
		targetingCacheService.load();

		seedMobileApplications();
		seedSegments();

		seedNewCheckoutFlow();
		seedCheckoutAnalyticsDashboard();
		seedPricingPageRedesign();
		seedBetaDashboardAccess();
		seedDarkModeDefault();
		seedExperimentalAiSummaries();
		seedTargetingRules();

		flagCacheService.load();
		targetingCacheService.load();
	}

	private void seedNewCheckoutFlow() {
		flagService.create(new FlagCreateRequest("new-checkout-flow", "New Checkout Flow",
				"Streamlined single-page checkout replacing the multi-step legacy flow.", FlagType.BOOLEAN,
				"Priya Sharma", List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")),
				List.of(), "seed"));

		enable("new-checkout-flow", EnvironmentKey.DEVELOPMENT, "off", List.of(rollout(100, "on")));
		enable("new-checkout-flow", EnvironmentKey.STAGING, "off", List.of(rollout(100, "on")));
		enable("new-checkout-flow", EnvironmentKey.PRODUCTION, "off", List.of(rollout(25, "on")));
	}

	/** Depends on new-checkout-flow - demonstrates the archive-dependency guard: archiving the
	 * checkout flow while this stays active is blocked. */
	private void seedCheckoutAnalyticsDashboard() {
		flagService.create(new FlagCreateRequest("checkout-analytics-dashboard", "Checkout Analytics Dashboard",
				"Funnel analytics panel for the new checkout flow; meaningless without it.", FlagType.BOOLEAN,
				"Diego Alvarez",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")),
				List.of("new-checkout-flow"), "seed"));

		enable("checkout-analytics-dashboard", EnvironmentKey.DEVELOPMENT, "off", List.of(rollout(100, "on")));
		enable("checkout-analytics-dashboard", EnvironmentKey.STAGING, "off", List.of(rollout(100, "on")));
	}

	/**
	 * The flagship experimentation demo: weighted variations, a JSON payload on the new-hero
	 * variation, and a live epsilon-greedy bandit in PRODUCTION seeded with uneven conversion data
	 * so a single reallocateNow() call visibly shifts weight toward the better-performing variation
	 * (new-hero) the first time the app starts.
	 */
	private void seedPricingPageRedesign() {
		flagService.create(new FlagCreateRequest("pricing-page-redesign", "Pricing Page Redesign",
				"New hero layout and copy for the pricing page.", FlagType.MULTIVARIATE, "Diego Alvarez",
				List.of(new VariationRequest("control", "Control", "control", 34, null, null),
						new VariationRequest("new-hero", "New hero", "new-hero", 33, PayloadType.JSON,
								"{\"heroImage\":\"redesign-v2.png\"}"),
						new VariationRequest("minimal", "Minimal", "minimal", 33, null, null)),
				List.of(), "seed"));

		StrategyRequest usRule = attribute("country", "US", "new-hero");

		enable("pricing-page-redesign", EnvironmentKey.STAGING, "control", List.of(usRule, rollout(50, "new-hero")));
		enable("pricing-page-redesign", EnvironmentKey.DEVELOPMENT, "control", List.of(usRule, rollout(100, "new-hero")));

		Map<String, Integer> initialWeights = new LinkedHashMap<>();
		initialWeights.put("control", 34);
		initialWeights.put("new-hero", 33);
		initialWeights.put("minimal", 33);
		enable("pricing-page-redesign", EnvironmentKey.PRODUCTION, "control",
				List.of(experimentSplit(initialWeights, BanditAlgorithm.EPSILON_GREEDY, 0.2)));

		seedEvents("pricing-page-redesign", EnvironmentKey.PRODUCTION, "control", 120, 9, "purchase");
		seedEvents("pricing-page-redesign", EnvironmentKey.PRODUCTION, "new-hero", 130, 18, "purchase");
		seedEvents("pricing-page-redesign", EnvironmentKey.PRODUCTION, "minimal", 90, 6, "purchase");
		experimentService.reallocateNow("pricing-page-redesign", EnvironmentKey.PRODUCTION, "seed");
	}

	private void seedBetaDashboardAccess() {
		flagService.create(new FlagCreateRequest("beta-dashboard-access", "Beta Dashboard Access",
				"Early access to the redesigned analytics dashboard for internal testers.", FlagType.BOOLEAN,
				"Jordan Lee", List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")),
				List.of(), "seed"));

		StrategyRequest testers = userId(List.of("user-1001", "user-1002", "user-1003"), "on");

		enable("beta-dashboard-access", EnvironmentKey.DEVELOPMENT, "off", List.of(testers));
		enable("beta-dashboard-access", EnvironmentKey.STAGING, "off", List.of(testers));
		flagService.archive("beta-dashboard-access", "seed");
	}

	private void seedDarkModeDefault() {
		flagService.create(new FlagCreateRequest("dark-mode-default", "Dark Mode Default",
				"Switch the default theme for new sessions to dark mode.", FlagType.BOOLEAN, "Sam Okafor",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(),
				"seed"));

		enable("dark-mode-default", EnvironmentKey.DEVELOPMENT, "off", List.of(rollout(100, "on")));

		// PRODUCTION: everyone currently gets "off"; a five-step, week-long progressive rollout is
		// moving them to "on", so the flags list and rule editor have a live rollout to show.
		Flag production = flagService.updateEnvironment("dark-mode-default", EnvironmentKey.PRODUCTION,
				new EnvironmentConfigRequest(true, "off", List.of(rollout(100, "off")), "seed"));
		String ruleId = production.getEnvironments().get(EnvironmentKey.PRODUCTION).getStrategies().get(0).getId();

		progressiveRolloutService.start("dark-mode-default", EnvironmentKey.PRODUCTION,
				new StartProgressiveRolloutRequest(ruleId, "on", null, null, 5, 7 * 24 * 60, "seed"));
	}

	/** Demonstrates the second bandit algorithm (Thompson sampling) with its own seeded data set. */
	private void seedExperimentalAiSummaries() {
		flagService.create(new FlagCreateRequest("experimental-ai-summaries", "Experimental AI Summaries",
				"AI-generated summaries on long-form content pages.", FlagType.MULTIVARIATE, "Priya Sharma",
				List.of(new VariationRequest("off", "Off", "off"), new VariationRequest("concise", "Concise", "concise"),
						new VariationRequest("detailed", "Detailed", "detailed")),
				List.of(), "seed"));

		Map<String, Integer> initialWeights = new LinkedHashMap<>();
		initialWeights.put("off", 34);
		initialWeights.put("concise", 33);
		initialWeights.put("detailed", 33);
		enable("experimental-ai-summaries", EnvironmentKey.STAGING, "off",
				List.of(experimentSplit(initialWeights, BanditAlgorithm.THOMPSON_SAMPLING, null)));

		seedEvents("experimental-ai-summaries", EnvironmentKey.STAGING, "off", 40, 2, "read-to-end");
		seedEvents("experimental-ai-summaries", EnvironmentKey.STAGING, "concise", 60, 10, "read-to-end");
		seedEvents("experimental-ai-summaries", EnvironmentKey.STAGING, "detailed", 50, 9, "read-to-end");
		experimentService.reallocateNow("experimental-ai-summaries", EnvironmentKey.STAGING, "seed");
	}

	/** The mobile registry behind "app version supported / unsupported" targeting. */
	private void seedMobileApplications() {
		mobileApplicationService.create(new MobileApplicationRequest("com.featureflag.mobile", "FeatureFlag Mobile", "2.4.0", "seed"));
		mobileApplicationService.create(new MobileApplicationRequest("com.featureflag.tablet", "FeatureFlag Tablet", "1.2.0", "seed"));
	}

	/**
	 * Segments are environment-scoped. Production gets the full set (explicit members, rule-based
	 * membership, an exclusion, and a non-user "organization" segment); staging gets a rule-only variant of
	 * beta-testers to show the same key meaning a different audience per environment.
	 */
	private void seedSegments() {
		ClauseRequest companyEmail = new ClauseRequest("user", "email", ClauseOperator.ENDS_WITH, List.of("@featureflag.dev"), false);

		segmentService.create(EnvironmentKey.PRODUCTION, new SegmentCreateRequest("beta-testers", "Beta testers",
				"Invited users and company staff trying features early.", null, List.of("user-1001", "user-1002", "user-1003"),
				List.of(), List.of(new SegmentRuleRequest(null, "Company email", List.of(companyEmail))), "seed"));

		segmentService.create(EnvironmentKey.PRODUCTION, new SegmentCreateRequest("enterprise-customers", "Enterprise customers",
				"Accounts on the enterprise plan, minus churned ones.", null, List.of(), List.of("user-9999"),
				List.of(new SegmentRuleRequest(null, "Enterprise plan",
						List.of(new ClauseRequest("user", "plan", ClauseOperator.IN, List.of("enterprise"), false)))),
				"seed"));

		segmentService.create(EnvironmentKey.PRODUCTION, new SegmentCreateRequest("mobile-beta", "Mobile beta",
				"Testers enrolled in the mobile beta program.", null, List.of("user-2001", "user-2002", "user-2003"), List.of(),
				List.of(), "seed"));

		segmentService.create(EnvironmentKey.PRODUCTION, new SegmentCreateRequest("internal-orgs", "Internal organizations",
				"Organizations owned by the company or its launch partners.", "organization",
				List.of("org-featureflag", "org-partner-sandbox"), List.of(), List.of(), "seed"));

		segmentService.create(EnvironmentKey.STAGING, new SegmentCreateRequest("beta-testers", "Beta testers",
				"Everyone with a company email; staging has no invite list.", null, List.of(), List.of(),
				List.of(new SegmentRuleRequest(null, "Company email", List.of(companyEmail))), "seed"));
	}

	/**
	 * Targeting rules layered over the seeded flags. Individual targets and rules sit above each flag's
	 * strategies in evaluation order, so they override the rollout and experiment configured underneath.
	 */
	private void seedTargetingRules() {
		ClauseRequest inBetaTesters = new ClauseRequest("user", null, ClauseOperator.IN_SEGMENT, List.of("beta-testers"), false);

		targetingService.createRule("new-checkout-flow", EnvironmentKey.PRODUCTION, new TargetingRuleRequest(
				"Beta testers get the new checkout", null, List.of(inBetaTesters), "on", null, null, "seed"));
		targetingService.createRule("new-checkout-flow", EnvironmentKey.PRODUCTION, new TargetingRuleRequest(
				"Germany holdout (paused)", false,
				List.of(new ClauseRequest("user", "country", ClauseOperator.IN, List.of("DE"), false)), "off", null, null, "seed"));

		targetingService.createRule("dark-mode-default", EnvironmentKey.DEVELOPMENT, new TargetingRuleRequest(
				"Unsupported app versions stay on light", null,
				List.of(new ClauseRequest("ld_application", null, ClauseOperator.APP_VERSION_SUPPORTED, List.of(), true)), "off", null,
				null, "seed"));
		targetingService.createRule("dark-mode-default", EnvironmentKey.DEVELOPMENT, new TargetingRuleRequest(
				"Supported Android apps get dark mode", null,
				List.of(new ClauseRequest("ld_application", null, ClauseOperator.APP_VERSION_SUPPORTED, List.of(), false),
						new ClauseRequest("ld_device", "os.name", ClauseOperator.IN, List.of("Android"), false)),
				"on", null, null, "seed"));

		targetingService.createRule("pricing-page-redesign", EnvironmentKey.PRODUCTION, new TargetingRuleRequest(
				"Internal organizations always see the new hero", null,
				List.of(new ClauseRequest("organization", null, ClauseOperator.IN_SEGMENT, List.of("internal-orgs"), false)),
				"new-hero", null, null, "seed"));

		// A progressive rollout ramping a targeting rule: staff (the STAGING beta-testers segment) currently
		// get "control" and are moved to "new-hero" over four hours. STAGING has no experiment on this
		// flag (that is PRODUCTION's), which is the one thing a rollout cannot coexist with.
		TargetingRule staffRule = targetingService.createRule("pricing-page-redesign", EnvironmentKey.STAGING, new TargetingRuleRequest(
				"Staff move to the new hero gradually", null, List.of(inBetaTesters), "control", null, null, "seed"));
		progressiveRolloutService.start("pricing-page-redesign", EnvironmentKey.STAGING,
				StartProgressiveRolloutRequest.forRule(staffRule.getId(), "new-hero", null, null, 4, 4 * 60, "seed"));

		targetingService.bulkTarget("experimental-ai-summaries", EnvironmentKey.STAGING,
				new BulkTargetRequest("user", "detailed", BulkOperation.ADD, List.of("user-1001", "user-1002"), "seed"));
		targetingService.bulkTarget("experimental-ai-summaries", EnvironmentKey.STAGING,
				new BulkTargetRequest("user", "concise", BulkOperation.ADD, List.of("qa-lead"), "seed"));
	}

	private StrategyRequest userId(List<String> userIds, String servedVariationKey) {
		return new StrategyRequest(null, StrategyType.USER_ID, userIds, null, null, 0, servedVariationKey);
	}

	private StrategyRequest attribute(String attribute, String value, String servedVariationKey) {
		return new StrategyRequest(null, StrategyType.ATTRIBUTE, List.of(), attribute, value, 0, servedVariationKey);
	}

	private StrategyRequest rollout(int percentage, String servedVariationKey) {
		return new StrategyRequest(null, StrategyType.PERCENTAGE_ROLLOUT, List.of(), null, null, percentage, servedVariationKey);
	}

	private StrategyRequest experimentSplit(Map<String, Integer> variationWeights, BanditAlgorithm banditAlgorithm,
			Double epsilon) {
		return new StrategyRequest(null, StrategyType.EXPERIMENT_SPLIT, List.of(), null, null, 0, null, variationWeights,
				banditAlgorithm, epsilon);
	}

	private void enable(String key, EnvironmentKey environment, String offVariationKey, List<StrategyRequest> strategies) {
		flagService.updateEnvironment(key, environment, new EnvironmentConfigRequest(true, offVariationKey, strategies, "seed"));
	}

	/** Inserts raw impression/conversion documents directly, bypassing the eval/conversion APIs, so
	 * the metrics dashboard has realistic, uneven data to demonstrate reallocation from first boot. */
	private void seedEvents(String flagKey, EnvironmentKey environment, String variationKey, int impressions, int conversions,
			String eventType) {
		for (int i = 0; i < impressions; i++) {
			impressionRepository.save(new ImpressionEvent(flagKey, environment, variationKey, "seed-user-" + variationKey + "-" + i,
					0, EvaluationReason.EXPERIMENT_ASSIGNED));
		}

		for (int i = 0; i < conversions; i++) {
			conversionRepository.save(new ConversionEvent(flagKey, environment, variationKey, "seed-user-" + variationKey + "-" + i,
					eventType, null));
		}
	}
}
