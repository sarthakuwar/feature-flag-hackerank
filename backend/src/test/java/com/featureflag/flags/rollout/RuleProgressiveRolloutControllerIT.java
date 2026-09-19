package com.featureflag.flags.rollout;

import static com.featureflag.flags.rollout.RolloutFixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;

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
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.ProgressiveRollout;
import com.featureflag.flags.StrategyType;
import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.EvaluationRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.StrategyRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.metrics.ConversionRepository;
import com.featureflag.flags.metrics.ImpressionEvent;
import com.featureflag.flags.metrics.ImpressionRepository;
import com.featureflag.flags.rollout.dto.RolloutStageRequest;
import com.featureflag.flags.rollout.dto.StartProgressiveRolloutRequest;
import com.featureflag.flags.rollout.dto.StopProgressiveRolloutRequest;
import com.featureflag.flags.targeting.ClauseOperator;
import com.featureflag.flags.targeting.dto.ClauseRequest;
import com.featureflag.flags.targeting.dto.RuleEnabledRequest;
import com.featureflag.flags.targeting.dto.RuleOrderRequest;
import com.featureflag.flags.targeting.dto.TargetingRuleRequest;
import com.featureflag.shared.MutableClock;

/**
 * Progressive rollouts that ramp a <em>targeting rule</em>, end to end: the real HTTP API, MongoDB,
 * the in-memory evaluation cache and the SSE stream, with time moved by hand through a
 * {@link MutableClock} so a whole schedule runs without sleeping. The background scheduler is off in
 * tests (application-test.properties); completion is triggered through the service it calls.
 *
 * <p>Every scenario uses the same flag: variations off/on/beta, PRODUCTION enabled, and one targeting
 * rule "Pro users" (plan = pro) serving "off". A 25% / 50% (after 1h) / 100% (after 2h) ramp moves
 * that rule's audience to "on".
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RuleProgressiveRolloutControllerIT {

	private static final String FLAG = "ramped-flag";

	private static final String ENV_URL = "/api/v1/flags/" + FLAG + "/environments/PRODUCTION";

	private static final String RULES = ENV_URL + "/rules";

	private static final String ROLLOUTS = ENV_URL + "/progressive-rollouts";

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

	// ---- starting ----

	@Test
	void startingARampOnARuleReturnsItsLiveProgressAndIsStoredAgainstTheRule() {
		String ruleId = flagWithProRule();

		ResponseEntity<Map> response = startOnRule(ruleId, "on");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Map<String, Object> rollout = data(response);
		assertThat(rollout.get("id")).asString().isNotBlank();
		assertThat(rollout.get("status")).isEqualTo("ACTIVE");
		assertThat(rollout.get("ruleId")).isEqualTo(ruleId);
		assertThat(rollout.get("strategyId")).isNull();
		assertThat(rollout.get("fromVariationKey")).isEqualTo("off");
		assertThat(rollout.get("toVariationKey")).isEqualTo("on");
		assertThat(rollout.get("currentPercentage")).isEqualTo(25);
		assertThat(rollout.get("currentStageIndex")).isEqualTo(0);
		assertThat(rollout.get("nextStageAt")).isEqualTo("2026-03-01T13:00:00Z");
		assertThat(rollout.get("completesAt")).isEqualTo("2026-03-01T14:00:00Z");

		ProgressiveRollout stored = flagRepository.findByKey(FLAG).orElseThrow().getEnvironments().get(EnvironmentKey.PRODUCTION)
				.getProgressiveRollouts().get(0);
		assertThat(stored.getRuleId()).as("persisted and reloaded through Mongo").isEqualTo(ruleId);
		assertThat(stored.getStrategyId()).isNull();
	}

	@Test
	void theRolloutIsListedAndFetchableAndFilterableByStatus() {
		String ruleId = flagWithProRule();
		String rolloutId = idOf(startOnRule(ruleId, "on"));

		List<Map<String, Object>> active = list(ROLLOUTS + "?status=ACTIVE");
		List<Map<String, Object>> stopped = list(ROLLOUTS + "?status=STOPPED");

		assertThat(active).extracting(rollout -> rollout.get("id")).containsExactly(rolloutId);
		assertThat(stopped).isEmpty();
		assertThat(data(restTemplate.getForEntity(ROLLOUTS + "/" + rolloutId, Map.class)).get("ruleId")).isEqualTo(ruleId);
	}

	@Test
	void startingIsAuditedAndBroadcastToSdkStreams() throws Exception {
		String ruleId = flagWithProRule();
		List<String> lines = new CopyOnWriteArrayList<>();
		CompletableFuture<HttpResponse<Void>> stream = subscribe(lines);
		Thread.sleep(400);

		startOnRule(ruleId, "on");

		long deadline = System.currentTimeMillis() + 5_000;

		while (System.currentTimeMillis() < deadline && lines.stream().noneMatch(line -> line.contains("ROLLOUT_START"))) {
			Thread.sleep(100);
		}

		stream.cancel(true);
		assertThat(lines).as("SDKs hear about it within seconds").anyMatch(line -> line.contains("flag-changed"));
		assertThat(lines).anyMatch(line -> line.contains("ROLLOUT_START"));

		List<Map<String, Object>> audit = (List<Map<String, Object>>) restTemplate.getForEntity("/api/v1/flags/" + FLAG + "/audit", Map.class)
				.getBody().get("data");
		assertThat(audit).anyMatch(event -> "ROLLOUT_START".equals(event.get("action")));
	}

	// ---- the ramp, seen through evaluation ----

	@Test
	void theRulesAudienceIsMovedToTheNewVariationStageByStageAndNeverMovesBack() {
		String ruleId = flagWithProRule();
		startOnRule(ruleId, "on");
		Set<String> moved = new HashSet<>();
		int previouslyMoved = 0;

		for (long minutes : new long[] { 0, 60, 119 }) {
			clock.set(T0.plus(Duration.ofMinutes(minutes)));
			int movedNow = 0;

			for (int index = 0; index < 150; index++) {
				String userId = "pro-" + index;
				Map<String, Object> result = evaluate(userId, Map.of("plan", "pro"));

				assertThat(result.get("reason")).isEqualTo("PROGRESSIVE_ROLLOUT");
				assertThat(result.get("matchedRuleId")).isEqualTo(ruleId);
				boolean isOn = "on".equals(result.get("variationKey"));

				if (moved.contains(userId)) {
					assertThat(isOn).as(userId + " moved earlier and must not move back at +" + minutes + "m").isTrue();
				}

				if (isOn) {
					movedNow++;
					moved.add(userId);
				}
			}

			assertThat(movedNow).as("at +" + minutes + "m").isGreaterThanOrEqualTo(previouslyMoved);
			previouslyMoved = movedNow;
		}

		clock.set(T0.plus(Duration.ofMinutes(120)));
		assertThat(previouslyMoved).as("at 50% about half moved").isBetween(45, 105);
		assertThat(evaluate("pro-1", Map.of("plan", "pro")).get("variationKey")).as("100% stage: everyone is on the new variation").isEqualTo("on");
	}

	@Test
	void usersOutsideTheRulesAudienceNeverSeeTheRamp() {
		String ruleId = flagWithProRule();
		startOnRule(ruleId, "on");
		clock.set(T0.plus(Duration.ofMinutes(120)));

		Map<String, Object> free = evaluate("free-user", Map.of("plan", "free"));

		assertThat(free.get("reason")).isEqualTo("FALLTHROUGH");
		assertThat(free.get("matchedRuleId")).isNull();
		assertThat(free.get("variationKey")).isEqualTo("off");
	}

	@Test
	void anAudienceEditedMidRampChangesWhoIsRampedButNotHowFarTheRampHasGone() {
		String ruleId = flagWithProRule();
		startOnRule(ruleId, "on");
		clock.set(T0.plus(Duration.ofMinutes(120)));

		ResponseEntity<Map> updated = put(RULES + "/" + ruleId, rule("Pro and team", "off", "plan", "pro", "team"));

		assertThat(updated.getStatusCode()).as("the audience (clauses) is free to change").isEqualTo(HttpStatus.OK);
		assertThat(evaluate("team-user", Map.of("plan", "team")).get("reason")).isEqualTo("PROGRESSIVE_ROLLOUT");
		assertThat(evaluate("team-user", Map.of("plan", "team")).get("variationKey")).isEqualTo("on");
	}

	@Test
	void rampedTrafficIsRecordedButKeptOutOfExperimentMetrics() {
		String ruleId = flagWithProRule();
		startOnRule(ruleId, "on");

		for (int index = 0; index < 5; index++) {
			evaluate("pro-" + index, Map.of("plan", "pro"));
		}

		List<ImpressionEvent> impressions = impressionRepository.findByFlagKeyAndEnvironment(FLAG, EnvironmentKey.PRODUCTION);

		assertThat(impressions).hasSize(5);
		assertThat(impressions).allSatisfy(impression -> assertThat(impression.isIncludedInExperiment()).isFalse());
	}

	// ---- rule position and state ----

	@Test
	void reorderingTheRampedRuleBelowAnotherRuleChangesWhoWinsButKeepsTheRamp() {
		String ruleId = flagWithProRule();
		String vipId = idOf(post(RULES, rule("VIPs", "beta", "tier", "vip")));
		startOnRule(ruleId, "on");
		clock.set(T0.plus(Duration.ofMinutes(120)));
		Map<String, String> vipPro = Map.of("plan", "pro", "tier", "vip");

		assertThat(evaluate("vip", vipPro).get("matchedRuleId")).as("Pro users is first").isEqualTo(ruleId);

		put(RULES + "/order", new RuleOrderRequest(List.of(vipId, ruleId), "tester"));

		Map<String, Object> afterReorder = evaluate("vip", vipPro);
		assertThat(afterReorder.get("matchedRuleId")).isEqualTo(vipId);
		assertThat(afterReorder.get("reason")).isEqualTo("RULE_MATCH");
		assertThat(evaluate("plain-pro", Map.of("plan", "pro")).get("reason")).as("everyone else in the audience is still ramped")
				.isEqualTo("PROGRESSIVE_ROLLOUT");
	}

	@Test
	void disablingTheRuleMidRampPausesItsEffectAndReEnablingResumesIt() {
		String ruleId = flagWithProRule();
		startOnRule(ruleId, "on");
		clock.set(T0.plus(Duration.ofMinutes(120)));

		patch(RULES + "/" + ruleId, new RuleEnabledRequest(false, "tester"));
		assertThat(evaluate("pro-1", Map.of("plan", "pro")).get("reason")).isEqualTo("FALLTHROUGH");

		patch(RULES + "/" + ruleId, new RuleEnabledRequest(true, "tester"));
		assertThat(evaluate("pro-1", Map.of("plan", "pro")).get("reason")).isEqualTo("PROGRESSIVE_ROLLOUT");
	}

	// ---- protecting a rule that is being ramped ----

	@Test
	void aRuleWithAnActiveRampCannotBeDeletedUntilTheRampIsStopped() {
		String ruleId = flagWithProRule();
		String rolloutId = idOf(startOnRule(ruleId, "on"));

		ResponseEntity<Map> refused = delete(RULES + "/" + ruleId);

		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(refused)).isEqualTo("ROLLOUT_IN_PROGRESS");
		assertThat(flagRepository.findByKey(FLAG).orElseThrow().getEnvironments().get(EnvironmentKey.PRODUCTION).getRules()).hasSize(1);

		stop(rolloutId, "off");

		assertThat(delete(RULES + "/" + ruleId).getStatusCode()).as("free to delete once the ramp has ended").isEqualTo(HttpStatus.OK);
	}

	@Test
	void whatTheRampedRuleServesCannotChangeWhileItIsBeingRamped() {
		String ruleId = flagWithProRule();
		startOnRule(ruleId, "on");

		ResponseEntity<Map> otherVariation = put(RULES + "/" + ruleId, rule("Pro users", "beta", "plan", "pro"));
		ResponseEntity<Map> asPercentageRollout = put(RULES + "/" + ruleId, new TargetingRuleRequest("Pro users", null,
				List.of(new ClauseRequest("user", "plan", ClauseOperator.IN, List.of("pro"), false)), null, Map.of("off", 50, "on", 50), null,
				"tester"));

		assertThat(otherVariation.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(otherVariation)).isEqualTo("ROLLOUT_IN_PROGRESS");
		assertThat(asPercentageRollout.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(evaluate("pro-1", Map.of("plan", "pro")).get("reason")).as("the ramp was left untouched").isEqualTo("PROGRESSIVE_ROLLOUT");
	}

	@Test
	void anUnrelatedEnvironmentSaveIsNotBlockedByARampOnARuleAndKeepsEverything() {
		String ruleId = flagWithProRule();
		startOnRule(ruleId, "on");

		ResponseEntity<Map> saved = restTemplate.exchange(ENV_URL, HttpMethod.PATCH,
				new HttpEntity<>(new EnvironmentConfigRequest(true, "off", List.of(), "tester")), Map.class);

		assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<String, Object> production = (Map<String, Object>) ((Map<String, Object>) data(saved).get("environments")).get("PRODUCTION");
		assertThat((List<?>) production.get("rules")).hasSize(1);
		assertThat((List<?>) production.get("progressiveRollouts")).hasSize(1);
		assertThat(list(ROLLOUTS + "?status=ACTIVE")).hasSize(1);
	}

	@Test
	void anExperimentCannotBeAddedWhileARuleIsBeingRamped() {
		String ruleId = flagWithProRule();
		startOnRule(ruleId, "on");
		StrategyRequest experiment = new StrategyRequest(null, StrategyType.EXPERIMENT_SPLIT, List.of(), null, null, 0, null,
				Map.of("off", 34, "on", 33, "beta", 33), com.featureflag.flags.BanditAlgorithm.NONE, null);

		ResponseEntity<Map> refused = restTemplate.exchange(ENV_URL, HttpMethod.PATCH,
				new HttpEntity<>(new EnvironmentConfigRequest(true, "off", List.of(experiment), "tester")), Map.class);

		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(refused)).isEqualTo("ROLLOUT_IN_PROGRESS");
	}

	@Test
	void theRolloutTargetVariationCannotBeRemovedFromTheFlagWhileRamping() {
		String ruleId = flagWithProRule();
		startOnRule(ruleId, "on");

		ResponseEntity<Map> removeTarget = restTemplate.exchange("/api/v1/flags/" + FLAG + "/variations/on", HttpMethod.DELETE,
				new HttpEntity<>(new ActingRequest("tester")), Map.class);
		ResponseEntity<Map> removeUnused = restTemplate.exchange("/api/v1/flags/" + FLAG + "/variations/beta", HttpMethod.DELETE,
				new HttpEntity<>(new ActingRequest("tester")), Map.class);

		assertThat(removeTarget.getStatusCode()).as("'on' is the ramp's destination").isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(removeUnused.getStatusCode()).as("'beta' is used by nothing").isEqualTo(HttpStatus.OK);
	}

	// ---- ending it ----

	@Test
	void whenTheFinalStageArrivesTheRuleIsRewrittenAndTheRampBecomesHistory() {
		String ruleId = flagWithProRule();
		String rolloutId = idOf(startOnRule(ruleId, "on"));
		clock.set(T0.plus(Duration.ofMinutes(120)));

		int completed = rolloutService.completeDueRollouts();

		assertThat(completed).isEqualTo(1);
		Map<String, Object> rollout = data(restTemplate.getForEntity(ROLLOUTS + "/" + rolloutId, Map.class));
		assertThat(rollout.get("status")).isEqualTo("COMPLETED");
		assertThat(rollout.get("resolvedVariationKey")).isEqualTo("on");
		assertThat(rollout.get("currentPercentage")).isEqualTo(100);
		assertThat(rollout.get("endedBy")).isEqualTo("system:rollout");

		Map<String, Object> ruleNow = ((List<Map<String, Object>>) data(restTemplate.getForEntity(ENV_URL + "/targeting", Map.class)).get("rules"))
				.get(0);
		assertThat(ruleNow.get("servedVariationKey")).as("the rule now permanently serves the new variation").isEqualTo("on");

		Map<String, Object> pro = evaluate("pro-1", Map.of("plan", "pro"));
		assertThat(pro.get("reason")).as("no longer a ramp, just a rule").isEqualTo("RULE_MATCH");
		assertThat(pro.get("variationKey")).isEqualTo("on");
		assertThat(list(ROLLOUTS + "?status=ACTIVE")).isEmpty();
	}

	@Test
	void completionDoesNothingBeforeTheFinalStage() {
		String ruleId = flagWithProRule();
		startOnRule(ruleId, "on");
		clock.set(T0.plus(Duration.ofMinutes(119)));

		assertThat(rolloutService.completeDueRollouts()).isZero();
		assertThat(list(ROLLOUTS + "?status=ACTIVE")).hasSize(1);
	}

	@Test
	void stoppingPartWayLetsYouKeepTheOldVariationForEveryone() {
		String ruleId = flagWithProRule();
		String rolloutId = idOf(startOnRule(ruleId, "on"));
		clock.set(T0.plus(Duration.ofMinutes(70)));

		ResponseEntity<Map> stopped = stop(rolloutId, "off");

		assertThat(stopped.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<String, Object> rollout = data(stopped);
		assertThat(rollout.get("status")).isEqualTo("STOPPED");
		assertThat(rollout.get("resolvedVariationKey")).isEqualTo("off");
		assertThat(rollout.get("currentPercentage")).as("it had reached the 50% stage").isEqualTo(50);

		for (int index = 0; index < 60; index++) {
			Map<String, Object> result = evaluate("pro-" + index, Map.of("plan", "pro"));

			assertThat(result.get("reason")).isEqualTo("RULE_MATCH");
			assertThat(result.get("variationKey")).isEqualTo("off");
		}
	}

	@Test
	void stoppingCanAlsoFinishEarlyOnTheNewVariationOrPickAnyVariation() {
		String ruleId = flagWithProRule();
		String rolloutId = idOf(startOnRule(ruleId, "on"));

		stop(rolloutId, "beta");

		assertThat(evaluate("pro-1", Map.of("plan", "pro")).get("variationKey")).isEqualTo("beta");
	}

	@Test
	void aStoppedRolloutCannotBeStoppedAgainAndTheRuleCanBeRampedAgain() {
		String ruleId = flagWithProRule();
		String rolloutId = idOf(startOnRule(ruleId, "on"));
		stop(rolloutId, "on");

		ResponseEntity<Map> again = stop(rolloutId, "off");
		ResponseEntity<Map> second = startOnRule(ruleId, "beta");

		assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(again)).isEqualTo("ROLLOUT_NOT_ACTIVE");
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(data(second).get("fromVariationKey")).as("the second ramp starts from where the first one ended").isEqualTo("on");
	}

	// ---- rejecting bad starts ----

	@Test
	void startingRequiresExactlyOneTargetAndReportsTheField() {
		String ruleId = flagWithProRule();

		ResponseEntity<Map> neither = post(ROLLOUTS, new StartProgressiveRolloutRequest(null, "on", null, null, 4, 60, "tester"));
		ResponseEntity<Map> both = post(ROLLOUTS, new StartProgressiveRolloutRequest("some-strategy", "on", null, null, 4, 60, "tester", ruleId));

		assertThat(neither.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(neither)).containsKey("ruleId");
		assertThat(both.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(both)).containsKey("ruleId");
	}

	@Test
	void startingOnAnUnknownRuleIsNotFound() {
		flagWithProRule();

		ResponseEntity<Map> response = startOnRule("no-such-rule", "on");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(response)).isEqualTo("RULE_NOT_FOUND");
	}

	@Test
	void aRuleThatAlreadySplitsTrafficCannotBeRamped() {
		flagWithProRule();
		String splitId = idOf(post(RULES, new TargetingRuleRequest("Split", null,
				List.of(new ClauseRequest("user", "plan", ClauseOperator.IN, List.of("team"), false)), null, Map.of("off", 50, "on", 50), null,
				"tester")));

		ResponseEntity<Map> response = startOnRule(splitId, "beta");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(response)).containsKey("ruleId");
	}

	@Test
	void aDisabledRuleCannotBeRamped() {
		String ruleId = flagWithProRule();
		patch(RULES + "/" + ruleId, new RuleEnabledRequest(false, "tester"));

		ResponseEntity<Map> response = startOnRule(ruleId, "on");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(response)).isEqualTo("RULE_DISABLED");
	}

	@Test
	void rampingToWhatTheRuleAlreadyServesIsRejected() {
		String ruleId = flagWithProRule();

		ResponseEntity<Map> response = startOnRule(ruleId, "off");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(response)).containsKey("toVariationKey");
	}

	@Test
	void onlyOneRampMayBeActiveInAnEnvironmentEvenAcrossDifferentRules() {
		String ruleId = flagWithProRule();
		String otherId = idOf(post(RULES, rule("Team", "off", "plan", "team")));
		startOnRule(ruleId, "on");

		ResponseEntity<Map> second = startOnRule(otherId, "on");

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(second)).isEqualTo("ROLLOUT_ALREADY_ACTIVE");
	}

	@Test
	void aRampOnOneEnvironmentDoesNotAffectTheSameRuleInAnother() {
		String ruleId = flagWithProRule();
		startOnRule(ruleId, "on");
		clock.set(T0.plus(Duration.ofMinutes(120)));

		ResponseEntity<Map> staging = restTemplate.postForEntity("/api/v1/eval/STAGING/" + FLAG,
				new EvaluationRequest("pro-1", Map.of("plan", "pro")), Map.class);

		assertThat(data(staging).get("reason")).as("staging has no rules or rollouts and its flag is off").isEqualTo("FLAG_DISABLED");
	}

	@Test
	void theLegacyStrategyRolloutStillWorksAlongsideRuleRollouts() {
		flagWithProRule();
		ResponseEntity<Map> patched = restTemplate.exchange(ENV_URL, HttpMethod.PATCH, new HttpEntity<>(new EnvironmentConfigRequest(true, "off",
				List.of(new StrategyRequest("legacy-1", StrategyType.ATTRIBUTE, List.of(), "country", "US", 0, "off")), "tester")), Map.class);
		assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<Map> started = post(ROLLOUTS, new StartProgressiveRolloutRequest("legacy-1", "on", null,
				List.of(new RolloutStageRequest(50, 0), new RolloutStageRequest(100, 60)), null, null, "tester"));

		assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(data(started).get("strategyId")).isEqualTo("legacy-1");
		assertThat(data(started).get("ruleId")).isNull();
		assertThat(evaluate("us-user", Map.of("country", "US")).get("reason")).isEqualTo("PROGRESSIVE_ROLLOUT");
		assertThat(evaluate("pro-1", Map.of("plan", "pro")).get("reason")).as("the targeting rule is not ramped by a strategy rollout")
				.isEqualTo("RULE_MATCH");
	}

	// ---- helpers ----

	/** Creates the flag, enables PRODUCTION and adds the "Pro users" rule; returns the rule's id. */
	private String flagWithProRule() {
		restTemplate.postForEntity("/api/v1/flags", new FlagCreateRequest(FLAG, FLAG, "desc", FlagType.MULTIVARIATE, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true"),
						new VariationRequest("beta", "Beta", "beta")), List.of(), "tester"), Map.class);
		restTemplate.exchange(ENV_URL, HttpMethod.PATCH, new HttpEntity<>(new EnvironmentConfigRequest(true, "off", List.of(), "tester")),
				Map.class);

		return idOf(post(RULES, rule("Pro users", "off", "plan", "pro")));
	}

	private static TargetingRuleRequest rule(String description, String serves, String attribute, String... values) {
		return new TargetingRuleRequest(description, null, List.of(new ClauseRequest("user", attribute, ClauseOperator.IN, List.of(values), false)),
				serves, null, null, "tester");
	}

	private ResponseEntity<Map> startOnRule(String ruleId, String to) {
		return post(ROLLOUTS, StartProgressiveRolloutRequest.forRule(ruleId, to, null,
				List.of(new RolloutStageRequest(25, 0), new RolloutStageRequest(50, 60), new RolloutStageRequest(100, 120)), null, null, "tester"));
	}

	private ResponseEntity<Map> stop(String rolloutId, String variationKey) {
		return post(ROLLOUTS + "/" + rolloutId + "/stop", new StopProgressiveRolloutRequest(variationKey, "operator"));
	}

	private Map<String, Object> evaluate(String userId, Map<String, String> attributes) {
		return data(restTemplate.postForEntity("/api/v1/eval/PRODUCTION/" + FLAG, new EvaluationRequest(userId, attributes), Map.class));
	}

	private ResponseEntity<Map> post(String url, Object body) {
		return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body), Map.class);
	}

	private ResponseEntity<Map> put(String url, Object body) {
		return restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body), Map.class);
	}

	private ResponseEntity<Map> patch(String url, Object body) {
		return restTemplate.exchange(url, HttpMethod.PATCH, new HttpEntity<>(body), Map.class);
	}

	private ResponseEntity<Map> delete(String url) {
		return restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(new ActingRequest("tester")), Map.class);
	}

	private List<Map<String, Object>> list(String url) {
		return (List<Map<String, Object>>) restTemplate.getForEntity(url, Map.class).getBody().get("data");
	}

	private static Map<String, Object> data(ResponseEntity<Map> response) {
		return (Map<String, Object>) response.getBody().get("data");
	}

	private static String idOf(ResponseEntity<Map> response) {
		return (String) data(response).get("id");
	}

	private static String errorCode(ResponseEntity<Map> response) {
		return (String) ((Map<String, Object>) response.getBody().get("error")).get("code");
	}

	private static Map<String, Object> fieldErrors(ResponseEntity<Map> response) {
		Map<String, Object> details = (Map<String, Object>) ((Map<String, Object>) response.getBody().get("error")).get("details");

		return (Map<String, Object>) details.get("fieldErrors");
	}

	private CompletableFuture<HttpResponse<Void>> subscribe(List<String> lines) {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/v1/stream/PRODUCTION"))
				.timeout(Duration.ofSeconds(15)).GET().build();

		return HttpClient.newHttpClient().sendAsync(request, responseInfo -> HttpResponse.BodySubscribers.fromLineSubscriber(new Flow.Subscriber<String>() {
			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(String item) {
				lines.add(item);
			}

			@Override
			public void onError(Throwable throwable) {
			}

			@Override
			public void onComplete() {
			}
		}));
	}
}
