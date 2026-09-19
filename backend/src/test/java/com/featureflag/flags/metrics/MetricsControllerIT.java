package com.featureflag.flags.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.BanditAlgorithm;
import com.featureflag.flags.EvaluationReason;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.experiment.dto.BanditConfigRequest;
import com.featureflag.flags.experiment.dto.SetWeightsRequest;
import com.featureflag.flags.metrics.dto.ConversionRequest;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MetricsControllerIT {

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

	@Autowired
	private ImpressionWriter impressionWriter;

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
	void recordingAConversionPersistsItToMongo() {
		createFlag("metrics-flag");

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/metrics/conversions",
				new ConversionRequest("metrics-flag", EnvironmentKey.PRODUCTION, "a", "user-1", "purchase", 19.99), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(conversionRepository.findByFlagKeyAndEnvironment("metrics-flag", EnvironmentKey.PRODUCTION)).hasSize(1);
	}

	@Test
	void recordingAConversionForAnUnknownFlagReturnsNotFound() {
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/metrics/conversions",
				new ConversionRequest("does-not-exist", EnvironmentKey.PRODUCTION, "a", "user-1", "purchase", null), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void recordingAConversionForAnUnknownVariationReturnsNotFound() {
		createFlag("metrics-flag-2");

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/metrics/conversions",
				new ConversionRequest("metrics-flag-2", EnvironmentKey.PRODUCTION, "does-not-exist", "user-1", "purchase", null),
				Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void recordingAConversionWithoutAVariationKeyIsAValidationErrorNotANotFound() {
		createFlag("metrics-flag-4");

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/metrics/conversions",
				new ConversionRequest("metrics-flag-4", EnvironmentKey.PRODUCTION, null, "user-1", "purchase", null), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
		assertThat(error.get("code")).isEqualTo("VALIDATION_ERROR");
		Map<String, Object> details = (Map<String, Object>) error.get("details");
		assertThat((Map<String, Object>) details.get("fieldErrors")).containsOnlyKeys("variationKey");
		assertThat(conversionRepository.count()).isZero();
	}

	@Test
	void recordingAConversionWithOversizedOrBlankFieldsReportsEachFieldError() {
		createFlag("metrics-flag-5");

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/metrics/conversions",
				new ConversionRequest("metrics-flag-5", EnvironmentKey.PRODUCTION, "a", "u".repeat(257), " ", 1e15), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
		Map<String, Object> details = (Map<String, Object>) error.get("details");
		assertThat((Map<String, Object>) details.get("fieldErrors")).containsOnlyKeys("userId", "eventType", "value");
		assertThat(conversionRepository.count()).isZero();
	}

	@Test
	void metricsCountsImpressionsThatAreStillQueuedForTheBackgroundWriter() {
		createFlag("metrics-flag-6");
		impressionRepository.deleteAll();
		impressionWriter.enqueue(new ImpressionEvent("metrics-flag-6", EnvironmentKey.PRODUCTION, "a", "user-1", 0,
				EvaluationReason.EXPERIMENT_ASSIGNED, true));

		Map<String, Object> variationTotals = variationCounts("metrics-flag-6", "impressions");

		assertThat(variationTotals.values().stream().mapToLong(count -> ((Number) count).longValue()).sum()).isEqualTo(1);
	}

	@Test
	void aConversionRightAfterAnEvaluationInheritsTheQueuedImpression() {
		createFlag("metrics-flag-7");
		impressionRepository.deleteAll();
		impressionWriter.enqueue(new ImpressionEvent("metrics-flag-7", EnvironmentKey.PRODUCTION, "a", "vip", 1,
				EvaluationReason.INDIVIDUAL_TARGET, false));

		restTemplate.postForEntity("/api/v1/metrics/conversions",
				new ConversionRequest("metrics-flag-7", EnvironmentKey.PRODUCTION, "a", "vip", "purchase", null), Map.class);

		assertThat(conversionRepository.findByFlagKeyAndEnvironment("metrics-flag-7", EnvironmentKey.PRODUCTION))
				.singleElement().satisfies(event -> assertThat(event.isIncludedInExperiment()).isFalse());
	}

	@Test
	void conversionsReallocateTrafficTowardTheWinningVariationWhenABanditIsConfigured() {
		createFlag("bandit-metrics-flag");
		restTemplate.exchange("/api/v1/flags/bandit-metrics-flag/environments/PRODUCTION/experiment/weights", HttpMethod.PUT,
				new HttpEntity<>(new SetWeightsRequest(Map.of("a", 50, "b", 50), "tester")), Map.class);
		restTemplate.exchange("/api/v1/flags/bandit-metrics-flag/environments/PRODUCTION/experiment/bandit", HttpMethod.PUT,
				new HttpEntity<>(new BanditConfigRequest(BanditAlgorithm.EPSILON_GREEDY, 0.1, "tester")), Map.class);

		for (String variation : List.of("a", "b")) {
			for (int index = 0; index < 100; index++) {
				impressionRepository.save(new ImpressionEvent("bandit-metrics-flag", EnvironmentKey.PRODUCTION, variation,
						"seed-" + variation + index, 0, EvaluationReason.EXPERIMENT_ASSIGNED));
			}
		}

		for (int index = 0; index < 40; index++) {
			ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/metrics/conversions",
					new ConversionRequest("bandit-metrics-flag", EnvironmentKey.PRODUCTION, "b", "winner-" + index, "purchase", null),
					Map.class);

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		}

		Map<String, Object> weights = variationCounts("bandit-metrics-flag", "currentWeight");

		assertThat(((Number) weights.get("b")).intValue()).isGreaterThan(((Number) weights.get("a")).intValue());
		assertThat(weights.values().stream().mapToInt(weight -> ((Number) weight).intValue()).sum()).isEqualTo(100);
	}

	@Test
	void metricsReturnsEveryVariationIncludingOnesWithNoActivityYet() {
		createFlag("metrics-flag-3");

		restTemplate.postForEntity("/api/v1/metrics/conversions",
				new ConversionRequest("metrics-flag-3", EnvironmentKey.PRODUCTION, "a", "user-1", "purchase", null), Map.class);
		restTemplate.postForEntity("/api/v1/metrics/conversions",
				new ConversionRequest("metrics-flag-3", EnvironmentKey.PRODUCTION, "a", "user-2", "purchase", null), Map.class);

		ResponseEntity<Map> response = restTemplate
				.getForEntity("/api/v1/metrics/metrics-flag-3/PRODUCTION", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
		List<Map<String, Object>> variations = (List<Map<String, Object>>) data.get("variations");
		assertThat(variations).extracting(v -> v.get("variationKey")).containsExactlyInAnyOrder("a", "b");

		Map<String, Object> variationA = variations.stream().filter(v -> "a".equals(v.get("variationKey"))).findFirst()
				.orElseThrow();
		assertThat(((Number) variationA.get("conversions")).longValue()).isEqualTo(2);

		Map<String, Object> variationB = variations.stream().filter(v -> "b".equals(v.get("variationKey"))).findFirst()
				.orElseThrow();
		assertThat(((Number) variationB.get("conversions")).longValue()).isEqualTo(0);
		assertThat(((Number) variationB.get("conversionRate")).doubleValue()).isEqualTo(0.0);
	}

	@Test
	void metricsForAnUnknownFlagReturnsNotFound() {
		ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/metrics/does-not-exist/PRODUCTION", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	private Map<String, Object> variationCounts(String flagKey, String field) {
		ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/metrics/" + flagKey + "/PRODUCTION", Map.class);
		Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
		List<Map<String, Object>> variations = (List<Map<String, Object>>) data.get("variations");
		Map<String, Object> byVariation = new LinkedHashMap<>();

		variations.forEach(variation -> byVariation.put((String) variation.get("variationKey"), variation.get(field)));

		return byVariation;
	}

	private void createFlag(String key) {
		FlagCreateRequest request = new FlagCreateRequest(key, key, "desc", FlagType.MULTIVARIATE, "owner",
				List.of(new VariationRequest("a", "A", "a"), new VariationRequest("b", "B", "b")), List.of(), "tester");

		restTemplate.postForEntity("/api/v1/flags", request, Map.class);
	}
}
