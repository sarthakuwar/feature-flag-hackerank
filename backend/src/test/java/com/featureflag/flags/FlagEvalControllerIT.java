package com.featureflag.flags;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.EvaluationRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.StrategyRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.metrics.ImpressionEvent;
import com.featureflag.flags.metrics.ImpressionRepository;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FlagEvalControllerIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private FlagRepository flagRepository;

	@Autowired
	private AuditRepository auditRepository;

	@Autowired
	private FlagCacheService flagCacheService;

	@Autowired
	private ImpressionRepository impressionRepository;

	@BeforeEach
	@AfterEach
	void cleanDatabase() {
		flagRepository.deleteAll();
		auditRepository.deleteAll();
		impressionRepository.deleteAll();
		flagCacheService.load();
	}

	@Test
	void evaluationReturnsOffVariationWhenEnvironmentDisabled() {
		createFlag("disabled-flag");

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/eval/PRODUCTION/disabled-flag",
				new EvaluationRequest("u1", Map.of()), Map.class);

		Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
		assertThat(data.get("variationKey")).isEqualTo("off");
		assertThat(data.get("reason")).isEqualTo("FLAG_DISABLED");
	}

	@Test
	void evaluationWithNoBodyTreatsTheCallerAsAnonymous() {
		createFlag("anon-flag");

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/eval/PRODUCTION/anon-flag", null, Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
		assertThat(data.get("flagKey")).isEqualTo("anon-flag");
	}

	@Test
	void evaluationHonorsAttributeStrategyOverRollout() {
		createFlag("targeted-flag");
		StrategyRequest rule = attributeStrategy("country", "US", "on");
		restTemplate.patchForObject("/api/v1/flags/targeted-flag/environments/PRODUCTION",
				new EnvironmentConfigRequest(true, "off", List.of(rule), "tester"), Map.class);

		ResponseEntity<Map> usResponse = restTemplate.postForEntity("/api/v1/eval/PRODUCTION/targeted-flag",
				new EvaluationRequest("u1", Map.of("country", "US")), Map.class);
		ResponseEntity<Map> caResponse = restTemplate.postForEntity("/api/v1/eval/PRODUCTION/targeted-flag",
				new EvaluationRequest("u2", Map.of("country", "CA")), Map.class);

		assertThat(((Map<String, Object>) usResponse.getBody().get("data")).get("variationKey")).isEqualTo("on");
		assertThat(((Map<String, Object>) caResponse.getBody().get("data")).get("variationKey")).isEqualTo("off");
	}

	@Test
	void bulkEvaluationReturnsEveryActiveFlagForEnvironment() {
		createFlag("flag-a");
		createFlag("flag-b");

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/eval/DEVELOPMENT", new EvaluationRequest("u1", Map.of()),
				Map.class);
		List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("data");

		assertThat(results).extracting(result -> result.get("flagKey")).containsExactlyInAnyOrder("flag-a", "flag-b");
	}

	@Test
	void evaluatingUnknownFlagReturnsNotFound() {
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/eval/PRODUCTION/does-not-exist",
				new EvaluationRequest("u1", Map.of()), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void bootstrapReturnsFullDefinitionsScopedToOneEnvironmentForActiveFlagsOnly() {
		createFlag("bootstrap-active");
		createFlag("bootstrap-archived");
		restTemplate.postForEntity("/api/v1/flags/bootstrap-archived/archive", new ActingRequest("tester"), Map.class);

		StrategyRequest rule = attributeStrategy("country", "US", "on");
		StrategyRequest rollout = new StrategyRequest(null, StrategyType.PERCENTAGE_ROLLOUT, List.of(), null, null, 40, "on");
		restTemplate.patchForObject("/api/v1/flags/bootstrap-active/environments/PRODUCTION",
				new EnvironmentConfigRequest(true, "off", List.of(rule, rollout), "tester"), Map.class);

		ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/eval/PRODUCTION/bootstrap", Map.class);
		List<Map<String, Object>> flags = (List<Map<String, Object>>) response.getBody().get("data");

		assertThat(flags).extracting(f -> f.get("key")).containsExactly("bootstrap-active");

		Map<String, Object> bootstrapped = flags.get(0);
		assertThat(bootstrapped).containsKeys("key", "name", "type", "variations", "environment");
		Map<String, Object> environmentPayload = (Map<String, Object>) bootstrapped.get("environment");
		assertThat(environmentPayload.get("enabled")).isEqualTo(true);
		List<Map<String, Object>> strategies = (List<Map<String, Object>>) environmentPayload.get("strategies");
		assertThat(strategies).hasSize(2);
	}

	/**
	 * Impression recording is synchronous (see MetricsService.recordImpression), so the impression is
	 * already stored when the HTTP response comes back: asserted immediately, deliberately with no
	 * polling, because a conversion sent right after an evaluation depends on exactly that.
	 */
	@Test
	void evaluatingASingleFlagRecordsTheImpressionBeforeTheResponseReturns() {
		createFlag("impression-flag");

		restTemplate.postForEntity("/api/v1/eval/PRODUCTION/impression-flag", new EvaluationRequest("user-1", Map.of()),
				Map.class);

		List<ImpressionEvent> events = impressionRepository.findByFlagKeyAndEnvironment("impression-flag", EnvironmentKey.PRODUCTION);

		assertThat(events).hasSize(1);
		assertThat(events.get(0).getUserId()).isEqualTo("user-1");
		assertThat(events.get(0).getVariationKey()).isEqualTo("off");
		assertThat(events.get(0).isIncludedInExperiment()).isTrue();
	}

	/** Bulk-evaluate isn't "one SDK evaluating one flag for one user," so it never emits impressions. */
	@Test
	void bulkEvaluationNeverRecordsImpressions() {
		createFlag("no-impression-flag");

		restTemplate.postForEntity("/api/v1/eval/PRODUCTION", new EvaluationRequest("user-1", Map.of()), Map.class);

		assertThat(impressionRepository.findByFlagKeyAndEnvironment("no-impression-flag", EnvironmentKey.PRODUCTION)).isEmpty();
	}

	private StrategyRequest attributeStrategy(String attribute, String value, String servedVariationKey) {
		return new StrategyRequest(null, StrategyType.ATTRIBUTE, List.of(), attribute, value, 0, servedVariationKey);
	}

	private void createFlag(String key) {
		FlagCreateRequest request = new FlagCreateRequest(key, key, "desc", FlagType.BOOLEAN, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(),
				"tester");

		restTemplate.postForEntity("/api/v1/flags", request, Map.class);
	}
}
