package com.featureflag.flags.targeting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.StrategyType;
import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.EvaluationRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.StrategyRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.metrics.ImpressionRepository;
import com.featureflag.flags.rollout.dto.RolloutStageRequest;
import com.featureflag.flags.rollout.dto.StartProgressiveRolloutRequest;
import com.featureflag.flags.targeting.dto.ClauseRequest;
import com.featureflag.flags.targeting.dto.TargetingRuleRequest;

/**
 * The two features share {@code EnvironmentConfig}, evaluation and the environment PATCH, so they are
 * exercised together here: a flag with targeting rules AND an active progressive rollout on one of
 * its strategies.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@SuppressWarnings({ "unchecked", "rawtypes" })
class TargetingProgressiveRolloutIT {

	private static final String FLAG = "combined-flag";

	private static final String ENV_URL = "/api/v1/flags/" + FLAG + "/environments/PRODUCTION";

	private static final String STRATEGY_ID = "pro-strategy";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private FlagRepository flagRepository;

	@Autowired
	private AuditRepository auditRepository;

	@Autowired
	private ImpressionRepository impressionRepository;

	@Autowired
	private FlagCacheService flagCacheService;

	@BeforeEach
	@AfterEach
	void cleanDatabase() {
		flagRepository.deleteAll();
		auditRepository.deleteAll();
		impressionRepository.deleteAll();
		flagCacheService.load();
	}

	@Test
	void aTargetingRuleWinsOverTheRolloutAndEveryoneElseInItsAudienceStillRampsUp() {
		startRolloutWithARule();

		Map<String, Object> ruled = evaluate("eu-pro-user", Map.of("plan", "pro", "region", "eu"));
		assertThat(ruled.get("reason")).as("rules are evaluated before strategies").isEqualTo("RULE_MATCH");
		assertThat(ruled.get("variationKey")).isEqualTo("off");

		Set<Object> variationsServed = new HashSet<>();

		for (int index = 0; index < 60; index++) {
			Map<String, Object> result = evaluate("pro-user-" + index, Map.of("plan", "pro"));

			assertThat(result.get("reason")).as("pro users outside the rule are still on the ramp").isEqualTo("PROGRESSIVE_ROLLOUT");
			variationsServed.add(result.get("variationKey"));
		}

		assertThat(variationsServed).as("a 50% ramp splits the audience between from and to").containsExactlyInAnyOrder("off", "on");
		assertThat(evaluate("free-user", Map.of("plan", "free")).get("reason")).isEqualTo("FALLTHROUGH");
	}

	@Test
	void anUnrelatedEnvironmentSaveKeepsBothTheRulesAndTheActiveRollout() {
		startRolloutWithARule();

		ResponseEntity<Map> saved = restTemplate.exchange(ENV_URL, HttpMethod.PATCH,
				new HttpEntity<>(new EnvironmentConfigRequest(true, "off", List.of(proStrategy()), "tester")), Map.class);

		assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<String, Object> production = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) saved.getBody().get("data"))
				.get("environments")).get("PRODUCTION");
		assertThat((List<?>) production.get("rules")).as("targeting rule survived").hasSize(1);
		assertThat((List<?>) production.get("progressiveRollouts")).as("rollout survived").hasSize(1);

		List<?> active = (List<?>) restTemplate.getForEntity(ENV_URL + "/progressive-rollouts?status=ACTIVE", Map.class).getBody()
				.get("data");
		assertThat(active).hasSize(1);
		assertThat(evaluate("eu-pro-user", Map.of("plan", "pro", "region", "eu")).get("reason")).isEqualTo("RULE_MATCH");
		assertThat(evaluate("pro-user-1", Map.of("plan", "pro")).get("reason")).isEqualTo("PROGRESSIVE_ROLLOUT");
	}

	// ---- helpers

	private void startRolloutWithARule() {
		restTemplate.postForEntity("/api/v1/flags", new FlagCreateRequest(FLAG, FLAG, "desc", FlagType.MULTIVARIATE, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(), "tester"),
				Map.class);
		restTemplate.exchange(ENV_URL, HttpMethod.PATCH,
				new HttpEntity<>(new EnvironmentConfigRequest(true, "off", List.of(proStrategy()), "tester")), Map.class);

		ResponseEntity<Map> rule = restTemplate.postForEntity(ENV_URL + "/rules", new TargetingRuleRequest("EU pro users stay put", null,
				List.of(new ClauseRequest("user", "plan", ClauseOperator.IN, List.of("pro"), false),
						new ClauseRequest("user", "region", ClauseOperator.IN, List.of("eu"), false)),
				"off", null, null, "tester"), Map.class);
		assertThat(rule.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		ResponseEntity<Map> rollout = restTemplate.postForEntity(ENV_URL + "/progressive-rollouts", new StartProgressiveRolloutRequest(
				STRATEGY_ID, "on", null, List.of(new RolloutStageRequest(50, 0), new RolloutStageRequest(100, 600)), null, null, "tester"),
				Map.class);
		assertThat(rollout.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private static StrategyRequest proStrategy() {
		return new StrategyRequest(STRATEGY_ID, StrategyType.ATTRIBUTE, List.of(), "plan", "pro", 0, "off");
	}

	private Map<String, Object> evaluate(String userId, Map<String, String> attributes) {
		return (Map<String, Object>) restTemplate
				.postForEntity("/api/v1/eval/" + EnvironmentKey.PRODUCTION + "/" + FLAG, new EvaluationRequest(userId, attributes), Map.class)
				.getBody().get("data");
	}
}
