package com.featureflag.flags.rollout;

import static com.featureflag.flags.rollout.RolloutFixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagEvaluationService;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.ProgressiveRollout;
import com.featureflag.flags.RolloutStatus;
import com.featureflag.flags.StrategyType;
import com.featureflag.flags.UserContext;
import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.EvaluationRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.StrategyRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.experiment.dto.SetWeightsRequest;
import com.featureflag.flags.metrics.ConversionRepository;
import com.featureflag.flags.metrics.ImpressionRepository;
import com.featureflag.flags.rollout.dto.RolloutStageRequest;
import com.featureflag.flags.rollout.dto.StartProgressiveRolloutRequest;
import com.featureflag.flags.rollout.dto.StopProgressiveRolloutRequest;
import com.featureflag.shared.MutableClock;

/**
 * The progressive rollout API end to end against a real Spring context and MongoDB. Time is a
 * {@link MutableClock} the tests move by hand, so a rollout can be walked through its stages
 * without sleeping; the background scheduler is disabled (application-test.properties) and
 * completion is triggered by calling the service the scheduler calls.
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProgressiveRolloutControllerIT {

	private static final String PROD = "PRODUCTION";

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		@Primary
		MutableClock testClock() {
			return new MutableClock(T0);
		}
	}

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private MutableClock clock;

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
	private FlagEvaluationService evaluationService;

	@Autowired
	private ProgressiveRolloutService rolloutService;

	@BeforeEach
	@AfterEach
	void reset() {
		clock.set(T0);
		flagRepository.deleteAll();
		auditRepository.deleteAll();
		impressionRepository.deleteAll();
		conversionRepository.deleteAll();
		flagCacheService.load();
	}

	// ---- start ----

	@Test
	void startingALinearRolloutReturnsItsLiveProgress() {
		String ruleId = flagWithRule("start-linear");

		ResponseEntity<Map> response = start("start-linear", PROD, linear(ruleId, "on", 4, 60));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Map<String, Object> rollout = data(response);
		assertThat(rollout.get("id")).asString().isNotBlank();
		assertThat(rollout.get("status")).isEqualTo("ACTIVE");
		assertThat(rollout.get("strategyId")).isEqualTo(ruleId);
		assertThat(rollout.get("fromVariationKey")).isEqualTo("off");
		assertThat(rollout.get("toVariationKey")).isEqualTo("on");
		assertThat(rollout.get("startedBy")).isEqualTo("tester");
		assertThat(rollout.get("currentPercentage")).isEqualTo(25);
		assertThat(rollout.get("currentStageIndex")).isEqualTo(0);
		assertThat(percentages(rollout)).containsExactly(25, 50, 75, 100);
		assertThat(Instant.parse((String) rollout.get("startAt"))).isEqualTo(T0);
		assertThat(Instant.parse((String) rollout.get("nextStageAt"))).isEqualTo(T0.plus(Duration.ofMinutes(20)));
		assertThat(Instant.parse((String) rollout.get("completesAt"))).isEqualTo(T0.plus(Duration.ofMinutes(60)));
	}

	@Test
	void startingWithExplicitStagesAndAFutureStartHoldsAtZeroUntilThen() {
		String ruleId = flagWithRule("start-custom");
		Instant later = T0.plus(Duration.ofHours(2));

		ResponseEntity<Map> response = start("start-custom", PROD, new StartProgressiveRolloutRequest(ruleId, "on", later,
				List.of(new RolloutStageRequest(5, 0), new RolloutStageRequest(30, 60), new RolloutStageRequest(100, 180)), null,
				null, "tester"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Map<String, Object> rollout = data(response);
		assertThat(rollout.get("currentPercentage")).isEqualTo(0);
		assertThat(rollout.get("currentStageIndex")).isEqualTo(-1);
		assertThat(Instant.parse((String) rollout.get("nextStageAt"))).isEqualTo(later);
		assertThat(percentages(rollout)).containsExactly(5, 30, 100);
	}

	@Test
	void theFlagResponseCarriesTheRolloutSoAListViewCanShowAProgressIcon() {
		String ruleId = flagWithRule("flag-embeds");
		String id = (String) data(start("flag-embeds", PROD, linear(ruleId, "on", 4, 60))).get("id");

		Map<String, Object> flag = data(restTemplate.getForEntity("/api/v1/flags/flag-embeds", Map.class));
		List<Map<String, Object>> rollouts = rolloutsOf(flag, PROD);

		assertThat(rollouts).hasSize(1);
		assertThat(rollouts.get(0).get("id")).isEqualTo(id);
		assertThat(rollouts.get(0).get("status")).isEqualTo("ACTIVE");
		assertThat(rolloutsOf(flag, "STAGING")).isEmpty();
	}

	@Test
	void startingPersistsTheRolloutWithoutChangingWhatTheRuleServes() {
		String ruleId = flagWithRule("start-persists");
		start("start-persists", PROD, linear(ruleId, "on", 4, 60));

		Flag stored = flagRepository.findByKey("start-persists").orElseThrow();
		ProgressiveRollout rollout = stored.getEnvironments().get(EnvironmentKey.PRODUCTION).activeProgressiveRollout().orElseThrow();

		assertThat(rollout.getStartAt()).isEqualTo(T0);
		assertThat(rollout.getStages()).hasSize(4);
		assertThat(stored.getEnvironments().get(EnvironmentKey.PRODUCTION).getStrategies().get(0).getServedVariationKey())
				.isEqualTo("off");
	}

	@Test
	void eachEnvironmentCanRunItsOwnRollout() {
		String ruleId = flagWithRule("per-environment");
		patchEnvironment("per-environment", "STAGING", true, attributeRule(ruleId, "off"));

		assertThat(start("per-environment", PROD, linear(ruleId, "on", 4, 60)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(start("per-environment", "STAGING", linear(ruleId, "on", 4, 60)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	// ---- start: validation and guards ----

	@Test
	void anInvalidRequestIsRejectedWithFieldLevelErrors() {
		String ruleId = flagWithRule("start-invalid");

		ResponseEntity<Map> response = start("start-invalid", PROD,
				new StartProgressiveRolloutRequest(ruleId, "ghost", null, null, null, null, " "));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
		Map<String, Object> fieldErrors = (Map<String, Object>) ((Map<String, Object>) error(response).get("details")).get("fieldErrors");
		assertThat(fieldErrors).containsKeys("changedBy", "toVariationKey", "stages");
	}

	@Test
	void anInvalidScheduleIsRejected() {
		String ruleId = flagWithRule("start-bad-schedule");

		ResponseEntity<Map> response = start("start-bad-schedule", PROD, new StartProgressiveRolloutRequest(ruleId, "on", null,
				List.of(new RolloutStageRequest(60, 0), new RolloutStageRequest(40, 10)), null, null, "tester"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
	}

	@Test
	void rollingOutWhatTheRuleAlreadyServesIsRejected() {
		String ruleId = flagWithRule("start-same");

		ResponseEntity<Map> response = start("start-same", PROD, linear(ruleId, "off", 4, 60));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void aSecondRolloutInTheSameEnvironmentIsRefused() {
		String ruleId = flagWithRule("start-twice");
		start("start-twice", PROD, linear(ruleId, "on", 4, 60));

		ResponseEntity<Map> second = start("start-twice", PROD, linear(ruleId, "beta", 4, 60));

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(second)).isEqualTo("ROLLOUT_ALREADY_ACTIVE");
	}

	@Test
	void anEnvironmentWithAnExperimentCannotStartARollout() {
		String ruleId = flagWithRule("start-experiment");
		restTemplate.exchange("/api/v1/flags/start-experiment/environments/PRODUCTION/experiment/weights", HttpMethod.PUT,
				new HttpEntity<>(new SetWeightsRequest(Map.of("off", 34, "on", 33, "beta", 33), "tester")), Map.class);

		ResponseEntity<Map> response = start("start-experiment", PROD, linear(ruleId, "on", 4, 60));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(response)).isEqualTo("EXPERIMENT_ACTIVE");
	}

	@Test
	void anUnknownRuleIsNotFound() {
		flagWithRule("start-no-rule");

		ResponseEntity<Map> response = start("start-no-rule", PROD, linear("nope", "on", 4, 60));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(response)).isEqualTo("STRATEGY_NOT_FOUND");
	}

	@Test
	void anUnknownFlagIsNotFound() {
		ResponseEntity<Map> response = start("ghost-flag", PROD, linear("rule", "on", 4, 60));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(response)).isEqualTo("FLAG_NOT_FOUND");
	}

	@Test
	void anArchivedFlagCannotStartARolloutButItsHistoryStaysReadable() {
		String ruleId = flagWithRule("start-archived");
		start("start-archived", PROD, linear(ruleId, "on", 4, 60));
		restTemplate.postForEntity("/api/v1/flags/start-archived/archive", new ActingRequest("tester"), Map.class);

		ResponseEntity<Map> restart = start("start-archived", PROD, linear(ruleId, "beta", 4, 60));
		ResponseEntity<Map> list = restTemplate.getForEntity(rolloutsUrl("start-archived", PROD), Map.class);

		assertThat(restart.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(restart)).isEqualTo("FLAG_ARCHIVED");
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(dataList(list)).hasSize(1);
	}

	// ---- editing the flag while a rollout runs ----

	@Test
	void anExperimentCannotBeIntroducedWhileARolloutRuns() {
		String ruleId = flagWithRule("guard-experiment");
		start("guard-experiment", PROD, linear(ruleId, "on", 4, 60));

		ResponseEntity<Map> response = restTemplate.exchange("/api/v1/flags/guard-experiment/environments/PRODUCTION/experiment/weights",
				HttpMethod.PUT, new HttpEntity<>(new SetWeightsRequest(Map.of("off", 34, "on", 33, "beta", 33), "tester")), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(response)).isEqualTo("ROLLOUT_IN_PROGRESS");
	}

	@Test
	void theRuleBeingRolledOutCannotBeRemovedOrRepointed() {
		String ruleId = flagWithRule("guard-rule");
		start("guard-rule", PROD, linear(ruleId, "on", 4, 60));

		ResponseEntity<Map> removed = patchEnvironment("guard-rule", PROD, true);
		ResponseEntity<Map> repointed = patchEnvironment("guard-rule", PROD, true, attributeRule(ruleId, "beta"));

		assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(removed)).isEqualTo("ROLLOUT_IN_PROGRESS");
		assertThat(repointed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(repointed)).isEqualTo("ROLLOUT_IN_PROGRESS");
	}

	@Test
	void editingTheEnvironmentKeepsTheRunningRolloutAttached() {
		String ruleId = flagWithRule("guard-preserve");
		String id = (String) data(start("guard-preserve", PROD, linear(ruleId, "on", 4, 60))).get("id");

		ResponseEntity<Map> response = patchEnvironment("guard-preserve", PROD, false, attributeRule(ruleId, "off"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map<String, Object>> rollouts = rolloutsOf(data(response), PROD);
		assertThat(rollouts).hasSize(1);
		assertThat(rollouts.get(0).get("id")).isEqualTo(id);
		assertThat(rollouts.get(0).get("status")).isEqualTo("ACTIVE");
	}

	// ---- reading ----

	@Test
	void listReturnsRolloutsNewestFirstAndCanFilterByStatus() {
		String ruleId = flagWithRule("list-rollouts");
		String first = (String) data(start("list-rollouts", PROD, linear(ruleId, "on", 4, 60))).get("id");
		stop("list-rollouts", PROD, first, "off");
		clock.advance(Duration.ofMinutes(5));
		String second = (String) data(start("list-rollouts", PROD, linear(ruleId, "beta", 4, 60))).get("id");

		List<Map<String, Object>> all = dataList(restTemplate.getForEntity(rolloutsUrl("list-rollouts", PROD), Map.class));
		List<Map<String, Object>> active = dataList(restTemplate.getForEntity(rolloutsUrl("list-rollouts", PROD) + "?status=ACTIVE", Map.class));
		List<Map<String, Object>> stopped = dataList(restTemplate.getForEntity(rolloutsUrl("list-rollouts", PROD) + "?status=STOPPED", Map.class));
		List<Map<String, Object>> completed = dataList(restTemplate.getForEntity(rolloutsUrl("list-rollouts", PROD) + "?status=COMPLETED", Map.class));

		assertThat(all).extracting(rollout -> rollout.get("id")).containsExactly(second, first);
		assertThat(active).extracting(rollout -> rollout.get("id")).containsExactly(second);
		assertThat(stopped).extracting(rollout -> rollout.get("id")).containsExactly(first);
		assertThat(completed).isEmpty();
	}

	@Test
	void getReturnsOneRolloutWithItsCurrentProgress() {
		String ruleId = flagWithRule("get-rollout");
		String id = (String) data(start("get-rollout", PROD, linear(ruleId, "on", 4, 60))).get("id");
		clock.advance(Duration.ofMinutes(45));

		Map<String, Object> rollout = data(restTemplate.getForEntity(rolloutsUrl("get-rollout", PROD) + "/" + id, Map.class));

		assertThat(rollout.get("currentPercentage")).isEqualTo(75);
		assertThat(rollout.get("currentStageIndex")).isEqualTo(2);
		assertThat(Instant.parse((String) rollout.get("nextStageAt"))).isEqualTo(T0.plus(Duration.ofMinutes(60)));
	}

	@Test
	void gettingAnUnknownRolloutIsNotFound() {
		flagWithRule("get-missing");

		ResponseEntity<Map> response = restTemplate.getForEntity(rolloutsUrl("get-missing", PROD) + "/nope", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(response)).isEqualTo("ROLLOUT_NOT_FOUND");
	}

	@Test
	void listingAFlagWithNoRolloutsIsEmpty() {
		flagWithRule("list-empty");

		ResponseEntity<Map> response = restTemplate.getForEntity(rolloutsUrl("list-empty", PROD), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(dataList(response)).isEmpty();
	}

	// ---- stop ----

	@Test
	void stoppingPausesTheReleaseOnTheOldVariation() {
		String ruleId = flagWithRule("stop-pause");
		String id = (String) data(start("stop-pause", PROD, linear(ruleId, "on", 4, 60))).get("id");
		clock.advance(Duration.ofMinutes(25));

		ResponseEntity<Map> response = stop("stop-pause", PROD, id, "off");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<String, Object> rollout = data(response);
		assertThat(rollout.get("status")).isEqualTo("STOPPED");
		assertThat(rollout.get("resolvedVariationKey")).isEqualTo("off");
		assertThat(rollout.get("currentPercentage")).isEqualTo(50);
		assertThat(rollout.get("endedBy")).isEqualTo("operator");
		assertThat(rollout.get("nextStageAt")).isNull();
		assertThat(servedByRule("stop-pause", ruleId)).isEqualTo("off");
	}

	@Test
	void stoppingCanCompleteTheReleaseAheadOfSchedule() {
		String ruleId = flagWithRule("stop-finish");
		String id = (String) data(start("stop-finish", PROD, linear(ruleId, "on", 4, 60))).get("id");

		stop("stop-finish", PROD, id, "on");

		assertThat(servedByRule("stop-finish", ruleId)).isEqualTo("on");
		assertThat(evaluate("stop-finish", new EvaluationRequest("anyone", Map.of("plan", "pro"))).get("variationKey")).isEqualTo("on");
	}

	@Test
	void stoppingCanServeAnyOtherVariationOnTheFlag() {
		String ruleId = flagWithRule("stop-other");
		String id = (String) data(start("stop-other", PROD, linear(ruleId, "on", 4, 60))).get("id");

		stop("stop-other", PROD, id, "beta");

		assertThat(servedByRule("stop-other", ruleId)).isEqualTo("beta");
	}

	@Test
	void afterAStopEveryoneInTheAudienceSeesTheChosenVariationImmediately() {
		String ruleId = flagWithRule("stop-immediate");
		String id = (String) data(start("stop-immediate", PROD, linear(ruleId, "on", 4, 60))).get("id");
		clock.advance(Duration.ofMinutes(45));
		stop("stop-immediate", PROD, id, "off");

		for (int index = 0; index < 60; index++) {
			Map<String, Object> result = evaluate("stop-immediate", new EvaluationRequest("user-" + index, Map.of("plan", "pro")));

			assertThat(result.get("variationKey")).isEqualTo("off");
			assertThat(result.get("reason")).isEqualTo("STRATEGY_MATCH");
		}
	}

	@Test
	void aStoppedRolloutCannotBeStoppedAgainAndFreesTheEnvironmentForANewOne() {
		String ruleId = flagWithRule("stop-twice");
		String id = (String) data(start("stop-twice", PROD, linear(ruleId, "on", 4, 60))).get("id");
		stop("stop-twice", PROD, id, "off");

		ResponseEntity<Map> again = stop("stop-twice", PROD, id, "on");
		ResponseEntity<Map> restarted = start("stop-twice", PROD, linear(ruleId, "beta", 4, 60));

		assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(again)).isEqualTo("ROLLOUT_NOT_ACTIVE");
		assertThat(restarted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(servedByRule("stop-twice", ruleId)).isEqualTo("off");
	}

	@Test
	void stoppingValidatesTheVariationAndTheRolloutId() {
		String ruleId = flagWithRule("stop-invalid");
		String id = (String) data(start("stop-invalid", PROD, linear(ruleId, "on", 4, 60))).get("id");

		ResponseEntity<Map> badVariation = stop("stop-invalid", PROD, id, "ghost");
		ResponseEntity<Map> unknown = stop("stop-invalid", PROD, "nope", "off");

		assertThat(badVariation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(unknown)).isEqualTo("ROLLOUT_NOT_FOUND");
		assertThat(rolloutStatus("stop-invalid", PROD, id)).isEqualTo("ACTIVE");
	}

	// ---- evaluation ----

	@Test
	void evaluationFollowsTheScheduleStageByStage() {
		String ruleId = flagWithRule("eval-stages");
		start("eval-stages", PROD, linear(ruleId, "on", 4, 60));
		int[] percentages = { 25, 50, 75, 100 };
		int[] minutes = { 0, 20, 40, 60 };

		for (int stage = 0; stage < 4; stage++) {
			clock.set(T0.plus(Duration.ofMinutes(minutes[stage])));

			for (int index = 0; index < 120; index++) {
				UserContext user = new UserContext("user-" + index, Map.of("plan", "pro"));
				boolean inRollout = evaluationService.bucketFor("eval-stages", user) < percentages[stage];
				Map<String, Object> result = evaluate("eval-stages", new EvaluationRequest(user.userId(), user.attributes()));

				assertThat(result.get("variationKey")).as("user %d at stage %d", index, stage).isEqualTo(inRollout ? "on" : "off");
			}
		}
	}

	@Test
	void resultsInsideARolloutReportTheRolloutReasonAndRule() {
		String ruleId = flagWithRule("eval-reason");
		start("eval-reason", PROD, linear(ruleId, "on", 4, 60));

		Map<String, Object> result = evaluateOne("eval-reason", new EvaluationRequest("user-1", Map.of("plan", "pro")));

		assertThat(result.get("reason")).isEqualTo("PROGRESSIVE_ROLLOUT");
		assertThat(result.get("matchedStrategyId")).isEqualTo(ruleId);
	}

	@Test
	void usersOutsideTheRulesAudienceAreNeverTouched() {
		String ruleId = flagWithRule("eval-audience");
		start("eval-audience", PROD, linear(ruleId, "on", 4, 60));
		clock.advance(Duration.ofDays(1));

		Map<String, Object> result = evaluate("eval-audience", new EvaluationRequest("user-1", Map.of("plan", "free")));

		assertThat(result.get("variationKey")).isEqualTo("off");
		assertThat(result.get("reason")).isEqualTo("FALLTHROUGH");
	}

	@Test
	void theBootstrapPayloadCarriesTheScheduleSoSdksCanEvaluateLocally() {
		String ruleId = flagWithRule("eval-bootstrap");
		start("eval-bootstrap", PROD, linear(ruleId, "on", 4, 60));

		ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/eval/PRODUCTION/bootstrap", Map.class);

		Map<String, Object> flag = ((List<Map<String, Object>>) response.getBody().get("data")).get(0);
		Map<String, Object> environment = (Map<String, Object>) flag.get("environment");
		List<Map<String, Object>> rollouts = (List<Map<String, Object>>) environment.get("progressiveRollouts");
		assertThat(rollouts).hasSize(1);
		assertThat(rollouts.get(0).get("strategyId")).isEqualTo(ruleId);
		assertThat((List<?>) rollouts.get(0).get("stages")).hasSize(4);
		assertThat(rollouts.get(0).get("startAt")).isNotNull();
	}

	@Test
	void aColdCacheReloadFromTheDatabaseEvaluatesTheSameWay() {
		String ruleId = flagWithRule("eval-restart");
		start("eval-restart", PROD, linear(ruleId, "on", 4, 60));
		clock.advance(Duration.ofMinutes(45));
		Map<String, Object> before = evaluate("eval-restart", new EvaluationRequest("user-5", Map.of("plan", "pro")));

		flagCacheService.load();
		Map<String, Object> after = evaluate("eval-restart", new EvaluationRequest("user-5", Map.of("plan", "pro")));

		assertThat(after.get("variationKey")).isEqualTo(before.get("variationKey"));
		assertThat(after.get("reason")).isEqualTo("PROGRESSIVE_ROLLOUT");
	}

	// ---- completion ----

	@Test
	void aRolloutThatReachesItsFinalStageIsCompletedAndTheRuleMadePermanent() {
		String ruleId = flagWithRule("complete-flow");
		String id = (String) data(start("complete-flow", PROD, linear(ruleId, "on", 4, 60))).get("id");

		clock.advance(Duration.ofMinutes(60));

		// Already serving the new variation to everyone, even before the completion tick has run.
		assertThat(evaluate("complete-flow", new EvaluationRequest("user-9", Map.of("plan", "pro"))).get("variationKey")).isEqualTo("on");
		assertThat(rolloutStatus("complete-flow", PROD, id)).isEqualTo("ACTIVE");

		assertThat(rolloutService.completeDueRollouts()).isEqualTo(1);

		Map<String, Object> rollout = data(restTemplate.getForEntity(rolloutsUrl("complete-flow", PROD) + "/" + id, Map.class));
		assertThat(rollout.get("status")).isEqualTo("COMPLETED");
		assertThat(rollout.get("currentPercentage")).isEqualTo(100);
		assertThat(rollout.get("resolvedVariationKey")).isEqualTo("on");
		assertThat(rollout.get("endedBy")).isEqualTo("system:rollout");
		assertThat(servedByRule("complete-flow", ruleId)).isEqualTo("on");

		Map<String, Object> result = evaluate("complete-flow", new EvaluationRequest("user-9", Map.of("plan", "pro")));
		assertThat(result.get("variationKey")).isEqualTo("on");
		assertThat(result.get("reason")).isEqualTo("STRATEGY_MATCH");
	}

	@Test
	void completionRunsOnlyOnceAndNotBeforeTheFinalStage() {
		String ruleId = flagWithRule("complete-once");
		start("complete-once", PROD, linear(ruleId, "on", 4, 60));

		clock.advance(Duration.ofMinutes(59));
		assertThat(rolloutService.completeDueRollouts()).isZero();

		clock.advance(Duration.ofMinutes(1));
		assertThat(rolloutService.completeDueRollouts()).isEqualTo(1);
		assertThat(rolloutService.completeDueRollouts()).isZero();
	}

	@Test
	void aCompletedRolloutFreesTheEnvironmentForTheNextOne() {
		String ruleId = flagWithRule("complete-next");
		start("complete-next", PROD, linear(ruleId, "on", 4, 60));
		clock.advance(Duration.ofMinutes(60));
		rolloutService.completeDueRollouts();

		ResponseEntity<Map> next = start("complete-next", PROD, linear(ruleId, "beta", 4, 60));

		assertThat(next.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(data(next).get("fromVariationKey")).isEqualTo("on");
	}

	@Test
	void aRolloutStoppedBeforeItsDueTimeIsNotCompletedByTheTick() {
		String ruleId = flagWithRule("complete-stopped");
		String id = (String) data(start("complete-stopped", PROD, linear(ruleId, "on", 4, 60))).get("id");
		stop("complete-stopped", PROD, id, "off");
		clock.advance(Duration.ofDays(1));

		assertThat(rolloutService.completeDueRollouts()).isZero();
		assertThat(rolloutStatus("complete-stopped", PROD, id)).isEqualTo("STOPPED");
		assertThat(servedByRule("complete-stopped", ruleId)).isEqualTo("off");
	}

	// ---- audit ----

	@Test
	void everyLifecycleStepIsRecordedInTheAuditTrail() {
		String ruleId = flagWithRule("audit-trail");
		String first = (String) data(start("audit-trail", PROD, linear(ruleId, "on", 4, 60))).get("id");
		clock.advance(Duration.ofMinutes(60));
		rolloutService.completeDueRollouts();
		String second = (String) data(start("audit-trail", PROD, linear(ruleId, "beta", 4, 60))).get("id");
		stop("audit-trail", PROD, second, "on");

		List<Map<String, Object>> history = dataList(restTemplate.getForEntity("/api/v1/flags/audit-trail/audit", Map.class));

		assertThat(history).extracting(event -> event.get("action")).contains("ROLLOUT_START", "ROLLOUT_COMPLETE", "ROLLOUT_STOP");
		assertThat(history.stream().filter(event -> "ROLLOUT_COMPLETE".equals(event.get("action"))).findFirst().orElseThrow()
				.get("changedBy")).isEqualTo("system:rollout");
		assertThat(history.stream().filter(event -> "ROLLOUT_STOP".equals(event.get("action"))).findFirst().orElseThrow()
				.get("changedBy")).isEqualTo("operator");
		assertThat(first).isNotEqualTo(second);
	}

	// ---- streaming ----

	@Test
	void startStopAndCompletionAreBroadcastToStreamSubscribers() throws Exception {
		String ruleId = flagWithRule("stream-lifecycle");
		String stoppedFlagRuleId = flagWithRule("stream-lifecycle-2");
		List<String> received = new CopyOnWriteArrayList<>();
		CompletableFuture<HttpResponse<Void>> stream = subscribe(received);
		Thread.sleep(300);

		start("stream-lifecycle", PROD, linear(ruleId, "on", 4, 60));
		assertThat(waitFor(() -> received.stream().anyMatch(line -> line.contains("ROLLOUT_START")))).isTrue();

		String id = (String) data(start("stream-lifecycle-2", PROD, linear(stoppedFlagRuleId, "on", 4, 60))).get("id");
		stop("stream-lifecycle-2", PROD, id, "off");
		assertThat(waitFor(() -> received.stream().anyMatch(line -> line.contains("ROLLOUT_STOP")))).isTrue();

		clock.advance(Duration.ofMinutes(60));
		rolloutService.completeDueRollouts();
		assertThat(waitFor(() -> received.stream().anyMatch(line -> line.contains("ROLLOUT_COMPLETE")))).isTrue();

		stream.cancel(true);
	}

	// ---- helpers ----

	/** Creates a multivariate flag (off/on/beta) with PRODUCTION enabled and a "plan = pro" rule serving "off"; returns the rule id. */
	private String flagWithRule(String key) {
		FlagCreateRequest request = new FlagCreateRequest(key, key, "desc", FlagType.MULTIVARIATE, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true"),
						new VariationRequest("beta", "Beta", "beta")),
				List.of(), "tester");
		restTemplate.postForEntity("/api/v1/flags", request, Map.class);

		ResponseEntity<Map> response = patchEnvironment(key, PROD, true, attributeRule(null, "off"));

		Map<String, Object> environments = (Map<String, Object>) data(response).get("environments");
		Map<String, Object> production = (Map<String, Object>) environments.get(PROD);

		return (String) ((List<Map<String, Object>>) production.get("strategies")).get(0).get("id");
	}

	private StrategyRequest attributeRule(String id, String served) {
		return new StrategyRequest(id, StrategyType.ATTRIBUTE, List.of(), "plan", "pro", 0, served);
	}

	private ResponseEntity<Map> patchEnvironment(String key, String environment, boolean enabled, StrategyRequest... strategies) {
		return restTemplate.exchange("/api/v1/flags/" + key + "/environments/" + environment, HttpMethod.PATCH,
				new HttpEntity<>(new EnvironmentConfigRequest(enabled, "off", List.of(strategies), "tester")), Map.class);
	}

	private StartProgressiveRolloutRequest linear(String ruleId, String to, int steps, int durationMinutes) {
		return new StartProgressiveRolloutRequest(ruleId, to, null, null, steps, durationMinutes, "tester");
	}

	private ResponseEntity<Map> start(String key, String environment, StartProgressiveRolloutRequest request) {
		return restTemplate.postForEntity(rolloutsUrl(key, environment), request, Map.class);
	}

	private ResponseEntity<Map> stop(String key, String environment, String rolloutId, String variationKey) {
		return restTemplate.postForEntity(rolloutsUrl(key, environment) + "/" + rolloutId + "/stop",
				new StopProgressiveRolloutRequest(variationKey, "operator"), Map.class);
	}

	private String rolloutsUrl(String key, String environment) {
		return "/api/v1/flags/" + key + "/environments/" + environment + "/progressive-rollouts";
	}

	/** Bulk-evaluate route: no impression is emitted, so sweeping many users stays cheap. */
	private Map<String, Object> evaluate(String key, EvaluationRequest request) {
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/eval/PRODUCTION", request, Map.class);

		return dataList(response).stream().filter(result -> key.equals(result.get("flagKey"))).findFirst().orElseThrow();
	}

	/** The single-flag route an SDK's variation call maps to. */
	private Map<String, Object> evaluateOne(String key, EvaluationRequest request) {
		return data(restTemplate.postForEntity("/api/v1/eval/PRODUCTION/" + key, request, Map.class));
	}

	private String servedByRule(String key, String ruleId) {
		Map<String, Object> flag = data(restTemplate.getForEntity("/api/v1/flags/" + key, Map.class));
		Map<String, Object> production = (Map<String, Object>) ((Map<String, Object>) flag.get("environments")).get(PROD);

		return (String) ((List<Map<String, Object>>) production.get("strategies")).stream()
				.filter(strategy -> ruleId.equals(strategy.get("id"))).findFirst().orElseThrow().get("servedVariationKey");
	}

	private String rolloutStatus(String key, String environment, String id) {
		return (String) data(restTemplate.getForEntity(rolloutsUrl(key, environment) + "/" + id, Map.class)).get("status");
	}

	private List<Map<String, Object>> rolloutsOf(Map<String, Object> flag, String environment) {
		Map<String, Object> config = (Map<String, Object>) ((Map<String, Object>) flag.get("environments")).get(environment);

		return (List<Map<String, Object>>) config.get("progressiveRollouts");
	}

	private List<Integer> percentages(Map<String, Object> rollout) {
		return ((List<Map<String, Object>>) rollout.get("stages")).stream().map(stage -> ((Number) stage.get("percentage")).intValue()).toList();
	}

	private Map<String, Object> data(ResponseEntity<Map> response) {
		return (Map<String, Object>) response.getBody().get("data");
	}

	private List<Map<String, Object>> dataList(ResponseEntity<Map> response) {
		return (List<Map<String, Object>>) response.getBody().get("data");
	}

	private Map<String, Object> error(ResponseEntity<Map> response) {
		return (Map<String, Object>) response.getBody().get("error");
	}

	private String errorCode(ResponseEntity<Map> response) {
		return (String) error(response).get("code");
	}

	private CompletableFuture<HttpResponse<Void>> subscribe(List<String> received) {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/v1/stream/PRODUCTION"))
				.timeout(Duration.ofSeconds(20)).GET().build();

		return HttpClient.newHttpClient().sendAsync(request,
				responseInfo -> HttpResponse.BodySubscribers.fromLineSubscriber(new Flow.Subscriber<String>() {
					@Override
					public void onSubscribe(Flow.Subscription subscription) {
						subscription.request(Long.MAX_VALUE);
					}

					@Override
					public void onNext(String item) {
						received.add(item);
					}

					@Override
					public void onError(Throwable throwable) {
					}

					@Override
					public void onComplete() {
					}
				}));
	}

	private boolean waitFor(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);

		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return true;
			}

			Thread.sleep(100);
		}

		return condition.getAsBoolean();
	}
}
