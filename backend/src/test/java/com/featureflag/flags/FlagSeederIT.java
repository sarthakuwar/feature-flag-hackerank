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
	void seededDependencyBlocksArchivingItsPrerequisite() {
		assertThatThrownBy(() -> flagService.archive("new-checkout-flow", "tester")).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("code", "FLAG_HAS_DEPENDENTS");

		Flag stillActive = flagRepository.findByKey("new-checkout-flow").orElseThrow();
		assertThat(stillActive.getLifecycle()).isEqualTo(FlagLifecycle.ACTIVE);
	}

	@Test
	void runningTheSeederAgainResetsToTheSameBaselineWithoutDuplicating() {
		long flagCountBefore = flagRepository.count();
		long auditCountBefore = auditRepository.count();

		flagSeeder.run(new DefaultApplicationArguments());

		assertThat(flagRepository.count()).isEqualTo(flagCountBefore);
		assertThat(auditRepository.count()).isEqualTo(auditCountBefore);
	}
}
