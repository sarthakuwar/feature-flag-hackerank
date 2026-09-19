package com.featureflag.flags.targeting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.flags.BanditAlgorithm;
import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.EvaluationReason;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.Strategy;
import com.featureflag.flags.StrategyType;
import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.EvaluationRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.StrategyRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.experiment.ExperimentService;
import com.featureflag.flags.metrics.ConversionEvent;
import com.featureflag.flags.metrics.ConversionRepository;
import com.featureflag.flags.metrics.ImpressionEvent;
import com.featureflag.flags.metrics.ImpressionRepository;
import com.featureflag.flags.metrics.dto.ConversionRequest;
import com.featureflag.flags.targeting.dto.BulkTargetRequest;
import com.featureflag.flags.targeting.dto.ClauseRequest;
import com.featureflag.flags.targeting.dto.TargetingRuleRequest;

/**
 * Targeting must never contaminate experiment results: users served by an individual target, or by a
 * rule that isn't included in the experiment, are hand-picked rather than randomly assigned, so their
 * traffic is kept out of every variation's impressions, out of the conversions that follow, and
 * therefore out of the bandit that reallocates traffic from those numbers.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@SuppressWarnings({ "unchecked", "rawtypes" })
class ExperimentInclusionIT {

	private static final String FLAG = "experiment-flag";

	private static final String RULES = "/api/v1/flags/" + FLAG + "/environments/PRODUCTION/rules";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private FlagRepository flagRepository;

	@Autowired
	private AuditRepository auditRepository;

	@Autowired
	private ImpressionRepository impressionRepository;

	@Autowired
	private ConversionRepository conversionRepository;

	@Autowired
	private FlagCacheService flagCacheService;

	@Autowired
	private ExperimentService experimentService;

	@Autowired
	private MongoTemplate mongoTemplate;

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
	void trafficFromTargetedUsersIsKeptOutOfImpressionsAndConversionsSoRatesCannotBeSkewed() throws Exception {
		createExperimentFlag(BanditAlgorithm.NONE, null);
		post(RULES, fixedRule("VIPs", "b", "plan", "vip"));
		post(RULES, rolloutRule("Trial users", "trial", false));
		post(RULES, rolloutRule("Beta users", "beta", true));
		post("/api/v1/flags/" + FLAG + "/environments/PRODUCTION/individual-targets/bulk",
				new BulkTargetRequest("user", "b", com.featureflag.flags.targeting.BulkOperation.ADD, List.of("target-1"), "tester"));

		int included = 0;
		int excluded = 0;

		// Everyone converts the instant they are served, exactly as an SDK would, so if excluded users
		// leaked into either side the rates would leave [0, 1].
		for (int index = 0; index < 20; index++) {
			evaluateAndConvert("organic-" + index, Map.of());
			included++;
		}

		for (int index = 0; index < 5; index++) {
			evaluateAndConvert("vip-" + index, Map.of("plan", "vip"));
			excluded++;
		}

		evaluateAndConvert("target-1", Map.of());
		excluded++;

		for (int index = 0; index < 6; index++) {
			evaluateAndConvert("beta-" + index, Map.of("plan", "beta"));
			included++;
		}

		for (int index = 0; index < 4; index++) {
			evaluateAndConvert("trial-" + index, Map.of("plan", "trial"));
			excluded++;
		}

		assertImpressionsRecorded(included + excluded);

		Map<String, Object> metrics = data(restTemplate.getForEntity("/api/v1/metrics/" + FLAG + "/PRODUCTION", Map.class));
		List<Map<String, Object>> variations = (List<Map<String, Object>>) metrics.get("variations");
		long impressions = variations.stream().mapToLong(v -> ((Number) v.get("impressions")).longValue()).sum();
		long conversions = variations.stream().mapToLong(v -> ((Number) v.get("conversions")).longValue()).sum();

		assertThat(impressions).as("only randomly assigned users count").isEqualTo(included);
		assertThat(conversions).as("and only their conversions").isEqualTo(included);
		assertThat(((Number) metrics.get("excludedImpressions")).longValue()).isEqualTo(excluded);
		assertThat(variations).allSatisfy(variation -> {
			assertThat(((Number) variation.get("conversions")).longValue()).isEqualTo(((Number) variation.get("impressions")).longValue());
			assertThat(((Number) variation.get("conversionRate")).doubleValue()).isLessThanOrEqualTo(1.0);
		});
		assertThat(conversionRepository.findByFlagKeyAndEnvironment(FLAG, EnvironmentKey.PRODUCTION)).as("stored, just not counted")
				.hasSize(included + excluded);
	}

	@Test
	void aConversionSentImmediatelyAfterAnEvaluationIsAlwaysAttributedCorrectly() {
		createExperimentFlag(BanditAlgorithm.NONE, null);
		post(RULES, fixedRule("VIPs", "b", "plan", "vip"));

		// Many back-to-back round trips: with an asynchronous impression write, some conversions would
		// beat their impression to the database and be wrongly counted as included.
		for (int index = 0; index < 60; index++) {
			evaluateAndConvert("vip-" + index, Map.of("plan", "vip"));
		}

		List<ConversionEvent> conversions = conversionRepository.findByFlagKeyAndEnvironment(FLAG, EnvironmentKey.PRODUCTION);

		assertThat(conversions).hasSize(60);
		assertThat(conversions).allSatisfy(conversion -> assertThat(conversion.isIncludedInExperiment())
				.as("conversion by " + conversion.getUserId()).isFalse());
	}

	@Test
	void evaluationStillSucceedsAndAnImpressionIsStoredEvenBeforeAnyConversionExists() {
		createExperimentFlag(BanditAlgorithm.NONE, null);

		Map<String, Object> result = evaluateRaw("first-visit", Map.of());

		assertThat(result.get("variationKey")).isNotNull();
		assertImpressionsRecorded(1);
		assertThat(conversionRepository.findByFlagKeyAndEnvironment(FLAG, EnvironmentKey.PRODUCTION)).isEmpty();
	}

	@Test
	void optingARuleInMakesItsTrafficCountFromThenOn() throws Exception {
		createExperimentFlag(BanditAlgorithm.NONE, null);
		String ruleId = (String) data(post(RULES, rolloutRule("Trial users", "trial", false))).get("id");

		evaluate("trial-before", Map.of("plan", "trial"));
		assertImpressionsRecorded(1);
		assertThat(countedImpressions()).isZero();

		put(RULES + "/" + ruleId, rolloutRule("Trial users", "trial", true));
		evaluate("trial-after", Map.of("plan", "trial"));
		assertImpressionsRecorded(2);

		assertThat(countedImpressions()).as("only the evaluation after opting in counts").isEqualTo(1);
	}

	@Test
	void evaluationAndTargetingExposeTheInclusionDecision() {
		createExperimentFlag(BanditAlgorithm.NONE, null);
		post(RULES, fixedRule("VIPs", "b", "plan", "vip"));
		post(RULES, rolloutRule("Beta users", "beta", true));

		Map<String, Object> vip = evaluateRaw("vip-1", Map.of("plan", "vip"));
		Map<String, Object> beta = evaluateRaw("beta-1", Map.of("plan", "beta"));
		Map<String, Object> organic = evaluateRaw("organic-1", Map.of());
		List<Map<String, Object>> rules = (List<Map<String, Object>>) data(
				restTemplate.getForEntity("/api/v1/flags/" + FLAG + "/environments/PRODUCTION/targeting", Map.class)).get("rules");

		assertThat(vip.get("includedInExperiment")).isEqualTo(false);
		assertThat(beta.get("includedInExperiment")).isEqualTo(true);
		assertThat(organic.get("includedInExperiment")).isEqualTo(true);
		assertThat(rules).extracting(rule -> rule.get("includeInExperiment")).containsExactly(false, true);
	}

	@Test
	void onlyARolloutRuleCanBeIncludedAndTheApiSaysSo() {
		createExperimentFlag(BanditAlgorithm.NONE, null);

		ResponseEntity<Map> response = post(RULES, new TargetingRuleRequest("Fixed but included", null,
				List.of(new ClauseRequest("user", "plan", com.featureflag.flags.targeting.ClauseOperator.IN, List.of("vip"), false)), "b", null,
				null, "tester", true));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Map<String, Object> details = (Map<String, Object>) ((Map<String, Object>) response.getBody().get("error")).get("details");
		assertThat((Map<String, Object>) details.get("fieldErrors")).containsKey("includeInExperiment");
	}

	@Test
	void theBanditIgnoresExcludedTrafficWhenReallocating() {
		createExperimentFlag(BanditAlgorithm.EPSILON_GREEDY, 0.2);

		// Genuine experiment: b clearly beats a (30% vs 5%).
		saveEvents("a", 100, 5, true);
		saveEvents("b", 100, 30, true);
		// Hand-picked traffic that, if it leaked in, would make a look like the winner (a: 52.5% vs b: 30%).
		saveEvents("a", 100, 100, false);

		experimentService.reallocateNow(FLAG, EnvironmentKey.PRODUCTION, "tester");

		Flag flag = flagRepository.findByKey(FLAG).orElseThrow();
		Strategy split = flag.getEnvironments().get(EnvironmentKey.PRODUCTION).getStrategies().stream()
				.filter(strategy -> strategy.getType() == StrategyType.EXPERIMENT_SPLIT).findFirst().orElseThrow();
		assertThat(split.getVariationWeights().get("b")).as("the real winner gets the traffic").isGreaterThan(split.getVariationWeights().get("a"));
	}

	@Test
	void eventsRecordedBeforeThisFieldExistedStillCountExactlyAsBefore() {
		createExperimentFlag(BanditAlgorithm.NONE, null);

		for (int index = 0; index < 3; index++) {
			mongoTemplate.getCollection("flag_impression_events").insertOne(new Document("flagKey", FLAG).append("environment", "PRODUCTION")
					.append("variationKey", "a").append("userId", "legacy-" + index).append("bucket", 1).append("reason", "EXPERIMENT_ASSIGNED"));
		}

		mongoTemplate.getCollection("flag_conversion_events").insertOne(new Document("flagKey", FLAG).append("environment", "PRODUCTION")
				.append("variationKey", "a").append("userId", "legacy-0").append("eventType", "purchase"));

		Map<String, Object> metrics = data(restTemplate.getForEntity("/api/v1/metrics/" + FLAG + "/PRODUCTION", Map.class));
		Map<String, Object> variationA = ((List<Map<String, Object>>) metrics.get("variations")).stream()
				.filter(variation -> "a".equals(variation.get("variationKey"))).findFirst().orElseThrow();

		assertThat(((Number) variationA.get("impressions")).longValue()).isEqualTo(3);
		assertThat(((Number) variationA.get("conversions")).longValue()).isEqualTo(1);
		assertThat(((Number) metrics.get("excludedImpressions")).longValue()).isZero();
	}

	// ---- helpers

	private void createExperimentFlag(BanditAlgorithm algorithm, Double epsilon) {
		restTemplate.postForEntity("/api/v1/flags", new FlagCreateRequest(FLAG, FLAG, "desc", FlagType.MULTIVARIATE, "owner",
				List.of(new VariationRequest("a", "A", "a"), new VariationRequest("b", "B", "b")), List.of(), "tester"), Map.class);

		Map<String, Integer> weights = new LinkedHashMap<>();
		weights.put("a", 50);
		weights.put("b", 50);
		StrategyRequest experiment = new StrategyRequest(null, StrategyType.EXPERIMENT_SPLIT, List.of(), null, null, 0, null, weights,
				algorithm, epsilon);

		restTemplate.exchange("/api/v1/flags/" + FLAG + "/environments/PRODUCTION", HttpMethod.PATCH,
				new HttpEntity<>(new EnvironmentConfigRequest(true, "a", List.of(experiment), "tester")), Map.class);
	}

	private static TargetingRuleRequest fixedRule(String description, String variation, String attribute, String value) {
		return new TargetingRuleRequest(description, null, List.of(new ClauseRequest("user", attribute,
				com.featureflag.flags.targeting.ClauseOperator.IN, List.of(value), false)), variation, null, null, "tester");
	}

	private static TargetingRuleRequest rolloutRule(String description, String plan, boolean includeInExperiment) {
		return new TargetingRuleRequest(description, null, List.of(new ClauseRequest("user", "plan",
				com.featureflag.flags.targeting.ClauseOperator.IN, List.of(plan), false)), null, Map.of("a", 50, "b", 50), null, "tester",
				includeInExperiment);
	}

	private String evaluate(String userId, Map<String, String> attributes) {
		return (String) evaluateRaw(userId, attributes).get("variationKey");
	}

	private Map<String, Object> evaluateRaw(String userId, Map<String, String> attributes) {
		return data(restTemplate.postForEntity("/api/v1/eval/PRODUCTION/" + FLAG, new EvaluationRequest(userId, attributes), Map.class));
	}

	private long countedImpressions() {
		return impressionRepository.findByFlagKeyAndEnvironment(FLAG, EnvironmentKey.PRODUCTION).stream()
				.filter(ImpressionEvent::isIncludedInExperiment).count();
	}

	private void saveEvents(String variationKey, int impressions, int conversions, boolean includedInExperiment) {
		for (int index = 0; index < impressions; index++) {
			impressionRepository.save(new ImpressionEvent(FLAG, EnvironmentKey.PRODUCTION, variationKey,
					"seed-" + includedInExperiment + "-" + variationKey + "-" + index, 0, EvaluationReason.EXPERIMENT_ASSIGNED,
					includedInExperiment));
		}

		for (int index = 0; index < conversions; index++) {
			conversionRepository.save(new ConversionEvent(FLAG, EnvironmentKey.PRODUCTION, variationKey,
					"seed-" + includedInExperiment + "-" + variationKey + "-" + index, "purchase", null, includedInExperiment));
		}
	}

	/** Impressions are written synchronously, so they are all there the moment the last evaluation returned: no waiting. */
	private void assertImpressionsRecorded(int expected) {
		assertThat(impressionRepository.findByFlagKeyAndEnvironment(FLAG, EnvironmentKey.PRODUCTION)).hasSize(expected);
	}

	/** The SDK pattern that used to race: evaluate, then convert immediately on the variation just served. */
	private String evaluateAndConvert(String userId, Map<String, String> attributes) {
		String variation = evaluate(userId, attributes);

		post("/api/v1/metrics/conversions", new ConversionRequest(FLAG, EnvironmentKey.PRODUCTION, variation, userId, "purchase", null));

		return variation;
	}

	private ResponseEntity<Map> post(String url, Object body) {
		return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body), Map.class);
	}

	private ResponseEntity<Map> put(String url, Object body) {
		return restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body), Map.class);
	}

	private static Map<String, Object> data(ResponseEntity<Map> response) {
		return (Map<String, Object>) response.getBody().get("data");
	}
}
