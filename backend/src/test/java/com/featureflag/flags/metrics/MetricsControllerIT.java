package com.featureflag.flags.metrics;

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

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.VariationRequest;
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

	private void createFlag(String key) {
		FlagCreateRequest request = new FlagCreateRequest(key, key, "desc", FlagType.MULTIVARIATE, "owner",
				List.of(new VariationRequest("a", "A", "a"), new VariationRequest("b", "B", "b")), List.of(), "tester");

		restTemplate.postForEntity("/api/v1/flags", request, Map.class);
	}
}
