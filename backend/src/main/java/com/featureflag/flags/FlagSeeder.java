package com.featureflag.flags;

import java.util.List;

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

/**
 * backend/build.gradle can't carry a Gradle "seed" task (it's a readonly_paths entry and has no
 * apply-from hook), so setup.sh's Gradle-seed lookup harmlessly no-ops. Seeding instead happens
 * here, in code, on every backend start: clear the flag collections, then insert a realistic
 * baseline, per GUIDELINES.md 3.4 ("seed logic clears application collections before inserting
 * the baseline"). Skipped for the "test" profile so integration tests own their own fixtures.
 */
@Component
@Profile("!test")
@Order(1)
public class FlagSeeder implements ApplicationRunner {

	private final FlagRepository flagRepository;

	private final AuditRepository auditRepository;

	private final FlagService flagService;

	private final FlagCacheService flagCacheService;

	public FlagSeeder(FlagRepository flagRepository, AuditRepository auditRepository, FlagService flagService,
			FlagCacheService flagCacheService) {
		this.flagRepository = flagRepository;
		this.auditRepository = auditRepository;
		this.flagService = flagService;
		this.flagCacheService = flagCacheService;
	}

	@Override
	public void run(ApplicationArguments args) {
		flagRepository.deleteAll();
		auditRepository.deleteAll();

		seedNewCheckoutFlow();
		seedCheckoutAnalyticsDashboard();
		seedPricingPageRedesign();
		seedBetaDashboardAccess();
		seedDarkModeDefault();
		seedExperimentalAiSummaries();

		flagCacheService.load();
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

	private void seedPricingPageRedesign() {
		flagService.create(new FlagCreateRequest("pricing-page-redesign", "Pricing Page Redesign",
				"New hero layout and copy for the pricing page.", FlagType.MULTIVARIATE, "Diego Alvarez",
				List.of(new VariationRequest("control", "Control", "control"),
						new VariationRequest("new-hero", "New hero", "new-hero"),
						new VariationRequest("minimal", "Minimal", "minimal")),
				List.of(), "seed"));

		StrategyRequest usRule = attribute("country", "US", "new-hero");

		enable("pricing-page-redesign", EnvironmentKey.STAGING, "control", List.of(usRule, rollout(50, "new-hero")));
		enable("pricing-page-redesign", EnvironmentKey.DEVELOPMENT, "control", List.of(usRule, rollout(100, "new-hero")));
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
	}

	private void seedExperimentalAiSummaries() {
		flagService.create(new FlagCreateRequest("experimental-ai-summaries", "Experimental AI Summaries",
				"AI-generated summaries on long-form content pages.", FlagType.MULTIVARIATE, "Priya Sharma",
				List.of(new VariationRequest("off", "Off", "off"), new VariationRequest("concise", "Concise", "concise"),
						new VariationRequest("detailed", "Detailed", "detailed")),
				List.of(), "seed"));
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

	private void enable(String key, EnvironmentKey environment, String offVariationKey, List<StrategyRequest> strategies) {
		flagService.updateEnvironment(key, environment, new EnvironmentConfigRequest(true, offVariationKey, strategies, "seed"));
	}
}
