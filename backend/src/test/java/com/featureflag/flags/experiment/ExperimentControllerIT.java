package com.featureflag.flags.experiment;

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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.flags.BanditAlgorithm;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagStreamService;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.SseTestClient;
import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.experiment.dto.BanditConfigRequest;
import com.featureflag.flags.experiment.dto.PromoteRequest;
import com.featureflag.flags.experiment.dto.SetWeightsRequest;
import com.featureflag.flags.metrics.ConversionRepository;
import com.featureflag.flags.metrics.ImpressionRepository;
import com.featureflag.flags.metrics.dto.ConversionRequest;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExperimentControllerIT {

	@LocalServerPort
	private int port;

	@Autowired
	private FlagStreamService flagStreamService;

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
	private ConversionRepository conversionRepository;

	@BeforeEach
	@AfterEach
	void cleanDatabase() {
		flagRepository.deleteAll();
		auditRepository.deleteAll();
		impressionRepository.deleteAll();
		conversionRepository.deleteAll();
		flagCacheService.load();
	}

	@Test
	void settingWeightsPersistsAnExperimentSplitStrategyReflectedInTheFlagResponse() {
		createFlag("weights-flag");

		ResponseEntity<Map> response = restTemplate.exchange("/api/v1/flags/weights-flag/environments/PRODUCTION/experiment/weights",
				HttpMethod.PUT, new HttpEntity<>(new SetWeightsRequest(Map.of("a", 40, "b", 60), "tester")), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Strategy strategy = experimentStrategy(response);
		assertThat(strategy.get("variationWeights")).isEqualTo(Map.of("a", 40, "b", 60));
	}

	@Test
	void settingWeightsThatDoNotSumToOneHundredIsRejected() {
		createFlag("weights-flag-2");

		ResponseEntity<Map> response = restTemplate.exchange("/api/v1/flags/weights-flag-2/environments/PRODUCTION/experiment/weights",
				HttpMethod.PUT, new HttpEntity<>(new SetWeightsRequest(Map.of("a", 40, "b", 40), "tester")), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void configuringABanditPersistsAlgorithmAndEpsilon() {
		createFlag("bandit-flag");
		setWeights("bandit-flag", Map.of("a", 50, "b", 50));

		ResponseEntity<Map> response = restTemplate.exchange("/api/v1/flags/bandit-flag/environments/PRODUCTION/experiment/bandit",
				HttpMethod.PUT, new HttpEntity<>(new BanditConfigRequest(BanditAlgorithm.EPSILON_GREEDY, 0.15, "tester")),
				Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Strategy strategy = experimentStrategy(response);
		assertThat(strategy.get("banditAlgorithm")).isEqualTo("EPSILON_GREEDY");
		assertThat(((Number) strategy.get("epsilon")).doubleValue()).isEqualTo(0.15);
	}

	@Test
	void reallocatingChangesWeightsAfterUnevenConversions() {
		createFlag("reallocate-flag");
		setWeights("reallocate-flag", Map.of("a", 50, "b", 50));
		configureBandit("reallocate-flag", BanditAlgorithm.EPSILON_GREEDY, 0.1);

		// Conversion rate is only meaningful relative to impressions, so seed matching impressions
		// directly (the same way FlagSeeder does) rather than relying on eval's bucketing to produce
		// them - this test is about the reallocation contract, not eval's sticky-bucketing math.
		seedImpressions("reallocate-flag", "a", 100);
		seedImpressions("reallocate-flag", "b", 100);

		for (int i = 0; i < 5; i++) {
			restTemplate.postForEntity("/api/v1/metrics/conversions",
					new ConversionRequest("reallocate-flag", EnvironmentKey.PRODUCTION, "a", "user-a-" + i, "purchase", null),
					Map.class);
		}
		for (int i = 0; i < 50; i++) {
			restTemplate.postForEntity("/api/v1/metrics/conversions",
					new ConversionRequest("reallocate-flag", EnvironmentKey.PRODUCTION, "b", "user-b-" + i, "purchase", null),
					Map.class);
		}

		ResponseEntity<Map> response = restTemplate.postForEntity(
				"/api/v1/flags/reallocate-flag/environments/PRODUCTION/experiment/reallocate", new ActingRequest("tester"),
				Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Strategy strategy = experimentStrategy(response);
		Map<String, Object> weights = (Map<String, Object>) strategy.get("variationWeights");
		assertThat(((Number) weights.get("b")).intValue()).isGreaterThan(((Number) weights.get("a")).intValue());
	}

	@Test
	void reallocatingWithoutABanditConfiguredReturnsConflict() {
		createFlag("no-bandit-flag");
		setWeights("no-bandit-flag", Map.of("a", 50, "b", 50));

		ResponseEntity<Map> response = restTemplate.postForEntity(
				"/api/v1/flags/no-bandit-flag/environments/PRODUCTION/experiment/reallocate", new ActingRequest("tester"),
				Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void promotingAWinnerSetsItToOneHundredPercentAndClearsTheBandit() {
		createFlag("promote-flag");
		setWeights("promote-flag", Map.of("a", 50, "b", 50));
		configureBandit("promote-flag", BanditAlgorithm.THOMPSON_SAMPLING, null);

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/flags/promote-flag/environments/PRODUCTION/experiment/promote",
				new PromoteRequest("b", "tester"), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Strategy strategy = experimentStrategy(response);
		assertThat(strategy.get("variationWeights")).isEqualTo(Map.of("a", 0, "b", 100));
		assertThat(strategy.get("banditAlgorithm")).isEqualTo("NONE");

		Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
		Map<String, Object> environments = (Map<String, Object>) data.get("environments");
		Map<String, Object> production = (Map<String, Object>) environments.get("PRODUCTION");
		assertThat(production.get("offVariationKey")).isEqualTo("b");
	}

	@Test
	void reallocationBroadcastsAnSseEventToSubscribers() {
		createFlag("stream-experiment-flag");
		setWeights("stream-experiment-flag", Map.of("a", 50, "b", 50));
		configureBandit("stream-experiment-flag", BanditAlgorithm.EPSILON_GREEDY, 0.1);

		try (SseTestClient stream = SseTestClient.open(port, flagStreamService, EnvironmentKey.PRODUCTION)) {
			restTemplate.postForEntity("/api/v1/flags/stream-experiment-flag/environments/PRODUCTION/experiment/reallocate",
					new ActingRequest("tester"), Map.class);

			stream.awaitLine("REALLOCATE");
		}
	}

	private void seedImpressions(String flagKey, String variationKey, int count) {
		for (int i = 0; i < count; i++) {
			impressionRepository.save(new com.featureflag.flags.metrics.ImpressionEvent(flagKey, EnvironmentKey.PRODUCTION,
					variationKey, "impression-user-" + variationKey + "-" + i, 0, com.featureflag.flags.EvaluationReason.EXPERIMENT_ASSIGNED));
		}
	}

	private void setWeights(String key, Map<String, Integer> weights) {
		restTemplate.exchange("/api/v1/flags/" + key + "/environments/PRODUCTION/experiment/weights", HttpMethod.PUT,
				new HttpEntity<>(new SetWeightsRequest(weights, "tester")), Map.class);
	}

	private void configureBandit(String key, BanditAlgorithm algorithm, Double epsilon) {
		restTemplate.exchange("/api/v1/flags/" + key + "/environments/PRODUCTION/experiment/bandit", HttpMethod.PUT,
				new HttpEntity<>(new BanditConfigRequest(algorithm, epsilon, "tester")), Map.class);
	}

	private Strategy experimentStrategy(ResponseEntity<Map> response) {
		Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
		Map<String, Object> environments = (Map<String, Object>) data.get("environments");
		Map<String, Object> production = (Map<String, Object>) environments.get("PRODUCTION");
		List<Map<String, Object>> strategies = (List<Map<String, Object>>) production.get("strategies");

		return new Strategy(strategies.stream().filter(strategy -> "EXPERIMENT_SPLIT".equals(strategy.get("type"))).findFirst()
				.orElseThrow());
	}

	private void createFlag(String key) {
		FlagCreateRequest request = new FlagCreateRequest(key, key, "desc", FlagType.MULTIVARIATE, "owner",
				List.of(new VariationRequest("a", "A", "a"), new VariationRequest("b", "B", "b")), List.of(), "tester");

		restTemplate.postForEntity("/api/v1/flags", request, Map.class);
	}

	/** Thin adapter over the raw {@code Map<String, Object>} strategy JSON, for readable field access in assertions. */
	private record Strategy(Map<String, Object> raw) {
		Object get(String field) {
			return raw.get(field);
		}
	}
}
