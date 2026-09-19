package com.featureflag.flags;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
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
import com.featureflag.flags.targeting.IndividualTarget;
import com.featureflag.flags.metrics.ImpressionWriter;

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

	@Autowired
	private ImpressionWriter impressionWriter;

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
	void anUnknownEnvironmentInTheEvaluationPathIsABadRequestNamingTheParameter() {
		ResponseEntity<Map> single = restTemplate.postForEntity("/api/v1/eval/BOGUS/some-flag", new EvaluationRequest("u1", Map.of()), Map.class);
		ResponseEntity<Map> bootstrap = restTemplate.getForEntity("/api/v1/eval/BOGUS/bootstrap", Map.class);

		for (ResponseEntity<Map> response : List.of(single, bootstrap)) {
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
			assertThat(error.get("code")).isEqualTo("VALIDATION_ERROR");
			Map<String, Object> fieldErrors = (Map<String, Object>) ((Map<String, Object>) error.get("details")).get("fieldErrors");
			assertThat(fieldErrors).containsOnlyKeys("environment");
			assertThat(fieldErrors.get("environment").toString()).contains("DEVELOPMENT", "STAGING", "PRODUCTION").doesNotContain("BOGUS");
		}
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

	/** Impressions are queued and written in the background; flush() is the deterministic seam that waits for them. */
	@Test
	void evaluatingASingleFlagRecordsTheImpression() {
		createFlag("impression-flag");

		restTemplate.postForEntity("/api/v1/eval/PRODUCTION/impression-flag", new EvaluationRequest("user-1", Map.of()),
				Map.class);
		impressionWriter.flush();

		List<ImpressionEvent> events = impressionRepository.findByFlagKeyAndEnvironment("impression-flag", EnvironmentKey.PRODUCTION);

		assertThat(events).hasSize(1);
		assertThat(events.get(0).getUserId()).isEqualTo("user-1");
		assertThat(events.get(0).getVariationKey()).isEqualTo("off");
		assertThat(events.get(0).isIncludedInExperiment()).as("a disabled flag assigns no one to a variation").isFalse();
	}

	/** Bulk-evaluate isn't "one SDK evaluating one flag for one user," so it never emits impressions. */
	@Test
	void bulkEvaluationNeverRecordsImpressions() {
		createFlag("no-impression-flag");

		restTemplate.postForEntity("/api/v1/eval/PRODUCTION", new EvaluationRequest("user-1", Map.of()), Map.class);
		impressionWriter.flush();

		assertThat(impressionRepository.findByFlagKeyAndEnvironment("no-impression-flag", EnvironmentKey.PRODUCTION)).isEmpty();
	}

	@Test
	void bootstrapExposesOnlyWhatLocalEvaluationNeedsAndAlwaysHasAnEnvironment() {
		createFlag("bootstrap-shape");
		Flag flag = flagRepository.findByKey("bootstrap-shape").orElseThrow();
		flag.setDependsOn(List.of("some-prerequisite"));
		EnvironmentConfig production = flag.getEnvironments().get(EnvironmentKey.PRODUCTION);
		production.setEnabled(true);
		production.getProgressiveRollouts().add(rollout("finished", RolloutStatus.COMPLETED));
		production.getProgressiveRollouts().add(rollout("running", RolloutStatus.ACTIVE));
		flag.getEnvironments().remove(EnvironmentKey.STAGING);
		flagRepository.save(flag);
		flagCacheService.load();

		Map<String, Object> bootstrapped = bootstrapOf("PRODUCTION", "bootstrap-shape");
		Map<String, Object> environment = (Map<String, Object>) bootstrapped.get("environment");
		List<Map<String, Object>> rollouts = (List<Map<String, Object>>) environment.get("progressiveRollouts");

		assertThat(bootstrapped.get("dependsOn")).isEqualTo(List.of("some-prerequisite"));
		assertThat(environment).containsOnlyKeys("enabled", "offVariationKey", "strategies", "individualTargets", "rules",
				"progressiveRollouts");
		assertThat(rollouts).as("history stays on the admin API").hasSize(1);
		assertThat(rollouts.get(0)).containsOnlyKeys("id", "strategyId", "ruleId", "fromVariationKey", "toVariationKey", "stages",
				"startAt");
		assertThat(rollouts.get(0).get("id")).isEqualTo("running");

		Map<String, Object> unconfigured = (Map<String, Object>) bootstrapOf("STAGING", "bootstrap-shape").get("environment");

		assertThat(unconfigured).as("a missing environment bootstraps as a disabled one").isNotNull();
		assertThat(unconfigured.get("enabled")).isEqualTo(false);
		assertThat(unconfigured.get("offVariationKey")).isEqualTo("off");
		assertThat((List<?>) unconfigured.get("rules")).isEmpty();
	}

	@Test
	void bothEvaluationRoutesRejectOversizedRequestsWithFieldErrors() {
		createFlag("limits-flag");
		EvaluationRequest tooLong = new EvaluationRequest("u".repeat(257), Map.of());
		EvaluationRequest atLimit = new EvaluationRequest("u".repeat(256), Map.of());

		for (String url : List.of("/api/v1/eval/PRODUCTION/limits-flag", "/api/v1/eval/PRODUCTION")) {
			ResponseEntity<Map> rejected = restTemplate.postForEntity(url, tooLong, Map.class);
			Map<String, Object> error = (Map<String, Object>) rejected.getBody().get("error");
			Map<String, Object> details = (Map<String, Object>) error.get("details");

			assertThat(rejected.getStatusCode()).as(url).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(error.get("code")).isEqualTo("VALIDATION_ERROR");
			assertThat((Map<String, Object>) details.get("fieldErrors")).containsKey("userId");
			assertThat(restTemplate.postForEntity(url, atLimit, Map.class).getStatusCode()).as(url).isEqualTo(HttpStatus.OK);
		}
	}

	@Test
	void anOversizedContextsMapIsRejectedBeforeAnyImpressionIsRecorded() {
		createFlag("contexts-flag");
		Map<String, EvaluationRequest.ContextRequest> contexts = new LinkedHashMap<>();

		for (int index = 0; index < 21; index++) {
			contexts.put("kind-" + index, new EvaluationRequest.ContextRequest("key", Map.of()));
		}

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/eval/PRODUCTION/contexts-flag",
				new EvaluationRequest("u", Map.of(), contexts), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(impressionRepository.findByFlagKeyAndEnvironment("contexts-flag", EnvironmentKey.PRODUCTION)).isEmpty();
	}

	@Test
	void aFailedPrerequisiteServesTheOffVariationNamesItAndStaysOutOfTheExperiment() {
		createFlag("base-flag");
		createFlag("dependent-flag", "base-flag");
		enable("base-flag", true);
		enable("dependent-flag", true);

		assertThat(evaluateOne("dependent-flag", "u1").get("reason")).isEqualTo("FALLTHROUGH");

		enable("base-flag", false);
		Map<String, Object> failed = evaluateOne("dependent-flag", "u1");

		assertThat(failed.get("reason")).isEqualTo("PREREQUISITE_FAILED");
		assertThat(failed.get("variationKey")).isEqualTo("off");
		assertThat(failed.get("failedPrerequisiteKey")).isEqualTo("base-flag");
		assertThat(failed.get("includedInExperiment")).isEqualTo(false);
		impressionWriter.flush();
		assertThat(impressionRepository.findByFlagKeyAndEnvironment("dependent-flag", EnvironmentKey.PRODUCTION))
				.extracting(ImpressionEvent::isIncludedInExperiment).containsOnly(false);
	}

	@Test
	void aFailedPrerequisiteBeatsAnIndividualTarget() {
		createFlag("base-flag");
		createFlag("dependent-flag", "base-flag");
		enable("dependent-flag", true);
		Flag dependent = flagRepository.findByKey("dependent-flag").orElseThrow();
		dependent.getEnvironments().get(EnvironmentKey.PRODUCTION).getIndividualTargets()
				.add(new IndividualTarget("user", "on", Set.of("vip")));
		flagRepository.save(dependent);
		flagCacheService.load();

		assertThat(evaluateOne("dependent-flag", "vip").get("reason")).isEqualTo("PREREQUISITE_FAILED");

		enable("base-flag", true);
		Map<String, Object> targeted = evaluateOne("dependent-flag", "vip");

		assertThat(targeted.get("reason")).isEqualTo("INDIVIDUAL_TARGET");
		assertThat(targeted.get("variationKey")).isEqualTo("on");
	}

	@Test
	void bulkEvaluationReturnsExactlyOneResultForEveryActiveFlagWhateverItsState() {
		createFlag("all-enabled");
		enable("all-enabled", true);
		createFlag("all-disabled");
		createFlag("all-failing", "all-enabled");
		enable("all-failing", true);
		createFlag("all-unconfigured");
		Flag unconfigured = flagRepository.findByKey("all-unconfigured").orElseThrow();
		unconfigured.getEnvironments().remove(EnvironmentKey.PRODUCTION);
		flagRepository.save(unconfigured);
		createFlag("all-archived");
		restTemplate.postForEntity("/api/v1/flags/all-archived/archive", new ActingRequest("tester"), Map.class);
		enable("all-enabled", false);
		flagCacheService.load();

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/eval/PRODUCTION", new EvaluationRequest("u1", Map.of()),
				Map.class);
		List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("data");

		assertThat(results).extracting(result -> result.get("flagKey")).containsExactlyInAnyOrder("all-enabled", "all-disabled",
				"all-failing", "all-unconfigured");
		assertThat(reasonOf(results, "all-failing")).isEqualTo("PREREQUISITE_FAILED");
		assertThat(reasonOf(results, "all-enabled")).isEqualTo("FLAG_DISABLED");
		assertThat(reasonOf(results, "all-unconfigured")).isEqualTo("FLAG_DISABLED");
	}

	private String reasonOf(List<Map<String, Object>> results, String key) {
		return results.stream().filter(result -> key.equals(result.get("flagKey"))).map(result -> (String) result.get("reason"))
				.findFirst().orElseThrow();
	}

	private Map<String, Object> bootstrapOf(String environment, String key) {
		ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/eval/" + environment + "/bootstrap", Map.class);
		List<Map<String, Object>> flags = (List<Map<String, Object>>) response.getBody().get("data");

		return flags.stream().filter(flag -> key.equals(flag.get("key"))).findFirst().orElseThrow();
	}

	private ProgressiveRollout rollout(String id, RolloutStatus status) {
		ProgressiveRollout rollout = new ProgressiveRollout();
		rollout.setId(id);
		rollout.setStrategyId("strategy-1");
		rollout.setFromVariationKey("off");
		rollout.setToVariationKey("on");
		rollout.setStages(List.of(new RolloutStage(50, 0), new RolloutStage(100, 60)));
		rollout.setStatus(status);
		rollout.setStartAt(Instant.parse("2026-03-01T12:00:00Z"));
		rollout.setCreatedAt(Instant.parse("2026-03-01T11:00:00Z"));
		rollout.setStartedBy("alice");
		rollout.setEndedBy("bob");
		rollout.setEndedAt(Instant.parse("2026-03-01T13:00:00Z"));
		rollout.setResolvedVariationKey("on");
		rollout.setFinalPercentage(100);

		return rollout;
	}

	private Map<String, Object> evaluateOne(String key, String userId) {
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/eval/PRODUCTION/" + key,
				new EvaluationRequest(userId, Map.of()), Map.class);

		return (Map<String, Object>) response.getBody().get("data");
	}

	private void enable(String key, boolean enabled) {
		restTemplate.patchForObject("/api/v1/flags/" + key + "/environments/PRODUCTION",
				new EnvironmentConfigRequest(enabled, "off", List.of(), "tester"), Map.class);
	}

	private StrategyRequest attributeStrategy(String attribute, String value, String servedVariationKey) {
		return new StrategyRequest(null, StrategyType.ATTRIBUTE, List.of(), attribute, value, 0, servedVariationKey);
	}

	private void createFlag(String key, String... dependsOn) {
		FlagCreateRequest request = new FlagCreateRequest(key, key, "desc", FlagType.BOOLEAN, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(dependsOn),
				"tester");

		restTemplate.postForEntity("/api/v1/flags", request, Map.class);
	}
}
