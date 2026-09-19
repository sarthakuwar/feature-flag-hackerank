package com.featureflag.flags.targeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.StrategyType;
import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.EvaluationRequest;
import com.featureflag.flags.dto.EvaluationRequest.ContextRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.StrategyRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.metrics.ImpressionRepository;
import com.featureflag.flags.targeting.dto.BulkTargetRequest;
import com.featureflag.flags.targeting.dto.ClauseRequest;
import com.featureflag.flags.targeting.dto.IndividualTargetRequest;
import com.featureflag.flags.targeting.dto.IndividualTargetsRequest;
import com.featureflag.flags.targeting.dto.MobileApplicationRequest;
import com.featureflag.flags.targeting.dto.MobileApplicationUpdateRequest;
import com.featureflag.flags.targeting.dto.RuleEnabledRequest;
import com.featureflag.flags.targeting.dto.RuleMoveRequest;
import com.featureflag.flags.targeting.dto.RuleOrderRequest;
import com.featureflag.flags.targeting.dto.SegmentCreateRequest;
import com.featureflag.flags.targeting.dto.SegmentMembersRequest;
import com.featureflag.flags.targeting.dto.SegmentRuleRequest;
import com.featureflag.flags.targeting.dto.SegmentUpdateRequest;
import com.featureflag.flags.targeting.dto.TargetingRuleRequest;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@SuppressWarnings({ "unchecked", "rawtypes" })
class TargetingControllerIT {

	private static final String FLAG = "targeted-flag";

	private static final String RULES = "/api/v1/flags/" + FLAG + "/environments/PRODUCTION/rules";

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private FlagRepository flagRepository;

	@Autowired
	private AuditRepository auditRepository;

	@Autowired
	private SegmentRepository segmentRepository;

	@Autowired
	private MobileApplicationRepository applicationRepository;

	@Autowired
	private ImpressionRepository impressionRepository;

	@Autowired
	private FlagCacheService flagCacheService;

	@Autowired
	private TargetingCacheService targetingCacheService;

	@BeforeEach
	@AfterEach
	void cleanDatabase() {
		flagRepository.deleteAll();
		auditRepository.deleteAll();
		segmentRepository.deleteAll();
		applicationRepository.deleteAll();
		impressionRepository.deleteAll();
		flagCacheService.load();
		targetingCacheService.load();
	}

	// ---- the headline flow: segment -> rule -> evaluation

	@Test
	void aFlagRuleTargetingASegmentServesMembersAndFollowsMembershipChangesImmediately() {
		createFlag(FLAG);
		enable(FLAG);
		createSegment("beta-testers", "Beta testers", List.of("tester-1", "tester-2"));
		ResponseEntity<Map> created = post(RULES, ruleRequest("Beta users", "on", segmentClause("beta-testers", false)));

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(data(created).get("category")).isEqualTo("SEGMENT");
		assertThat(data(created).get("id")).isNotNull();

		Map<String, Object> member = evaluate(FLAG, "tester-1");
		assertThat(member.get("variationKey")).isEqualTo("on");
		assertThat(member.get("reason")).isEqualTo("RULE_MATCH");
		assertThat(member.get("matchedRuleId")).isEqualTo(data(created).get("id"));
		assertThat(evaluate(FLAG, "outsider").get("reason")).isEqualTo("FALLTHROUGH");

		post("/api/v1/segments/PRODUCTION/beta-testers/members",
				new SegmentMembersRequest(MemberList.INCLUDED, BulkOperation.ADD, List.of("outsider"), "tester"));
		post("/api/v1/segments/PRODUCTION/beta-testers/members",
				new SegmentMembersRequest(MemberList.INCLUDED, BulkOperation.REMOVE, List.of("tester-1"), "tester"));

		assertThat(evaluate(FLAG, "outsider").get("reason")).as("added to the segment, flag untouched").isEqualTo("RULE_MATCH");
		assertThat(evaluate(FLAG, "tester-1").get("reason")).as("removed from the segment").isEqualTo("FALLTHROUGH");
	}

	@Test
	void aNotInSegmentRuleAndAnExcludedMemberBehaveAsDocumented() {
		createFlag(FLAG);
		enable(FLAG);
		createSegment("beta-testers", "Beta testers", List.of("tester-1"));
		post("/api/v1/segments/PRODUCTION/beta-testers/members",
				new SegmentMembersRequest(MemberList.EXCLUDED, BulkOperation.ADD, List.of("blocked"), "tester"));
		post(RULES, ruleRequest("Everyone but beta", "off", segmentClause("beta-testers", true)));
		patchConfig(FLAG, "on");

		assertThat(evaluate(FLAG, "tester-1").get("variationKey")).isEqualTo("on");
		assertThat(evaluate(FLAG, "stranger").get("variationKey")).isEqualTo("off");
		assertThat(evaluate(FLAG, "blocked").get("variationKey")).as("excluded = not in segment").isEqualTo("off");
	}

	@Test
	void rulesAndIndividualTargetsSurviveARoundTripThroughMongoAndACacheReload() {
		createFlag(FLAG);
		enable(FLAG);
		createSegment("beta-testers", "Beta testers", List.of("tester-1"));
		post(RULES, ruleRequest("Beta", "on", segmentClause("beta-testers", false)));
		post("/api/v1/flags/" + FLAG + "/environments/PRODUCTION/individual-targets/bulk",
				new BulkTargetRequest("user", "off", BulkOperation.ADD, List.of("vip"), "tester"));

		flagCacheService.load();
		targetingCacheService.load();

		Flag reloaded = flagRepository.findByKey(FLAG).orElseThrow();
		TargetingRule rule = reloaded.getEnvironments().get(EnvironmentKey.PRODUCTION).getRules().get(0);
		assertThat(rule.getClauses().get(0).getOperator()).isEqualTo(ClauseOperator.IN_SEGMENT);
		assertThat(rule.getClauses().get(0).getValues()).containsExactly("beta-testers");
		assertThat(reloaded.getEnvironments().get(EnvironmentKey.PRODUCTION).getIndividualTargets().get(0).getKeys()).containsExactly("vip");
		assertThat(evaluate(FLAG, "tester-1").get("reason")).isEqualTo("RULE_MATCH");
		assertThat(evaluate(FLAG, "vip").get("reason")).isEqualTo("INDIVIDUAL_TARGET");
	}

	// ---- rule lifecycle over HTTP

	@Test
	void ruleCrudEnableDisableReorderAndMoveWorkEndToEnd() {
		createFlag(FLAG);
		enable(FLAG);
		String proId = idOf(post(RULES, ruleRequest("Pro", "on", attributeClause("plan", ClauseOperator.IN, "pro"))));
		String usId = idOf(post(RULES, ruleRequest("US", "off", attributeClause("country", ClauseOperator.IN, "US"))));
		Map<String, String> proUs = Map.of("plan", "pro", "country", "US");

		assertThat(evaluate(FLAG, "u1", proUs).get("matchedRuleId")).as("Pro is first").isEqualTo(proId);

		ResponseEntity<Map> moved = post(RULES + "/" + usId + "/move", new RuleMoveRequest(MoveDirection.UP, "tester"));
		assertThat(ruleIds(moved)).containsExactly(usId, proId);
		assertThat(evaluate(FLAG, "u1", proUs).get("matchedRuleId")).as("moved US up").isEqualTo(usId);

		ResponseEntity<Map> reordered = put(RULES + "/order", new RuleOrderRequest(List.of(proId, usId), "tester"));
		assertThat(ruleIds(reordered)).containsExactly(proId, usId);

		ResponseEntity<Map> disabled = patch(RULES + "/" + proId, new RuleEnabledRequest(false, "tester"));
		assertThat(data(disabled).get("enabled")).isEqualTo(false);
		assertThat(evaluate(FLAG, "u1", proUs).get("matchedRuleId")).as("disabled Pro is skipped, kept in place").isEqualTo(usId);
		assertThat(ruleIds(get("/api/v1/flags/" + FLAG + "/environments/PRODUCTION/targeting"))).containsExactly(proId, usId);

		patch(RULES + "/" + proId, new RuleEnabledRequest(true, "tester"));
		assertThat(evaluate(FLAG, "u1", proUs).get("matchedRuleId")).isEqualTo(proId);

		ResponseEntity<Map> updated = put(RULES + "/" + proId, ruleRequest("Pro or Team", "on", attributeClause("plan", ClauseOperator.IN, "pro", "team")));
		assertThat(data(updated).get("description")).isEqualTo("Pro or Team");
		assertThat(evaluate(FLAG, "u2", Map.of("plan", "team")).get("matchedRuleId")).isEqualTo(proId);

		ResponseEntity<Map> deleted = delete(RULES + "/" + proId);
		assertThat(ruleIds(deleted)).containsExactly(usId);
		assertThat(evaluate(FLAG, "u2", Map.of("plan", "team")).get("reason")).isEqualTo("FALLTHROUGH");
	}

	@Test
	void aRuleCanServeAPercentageRolloutAndIsExposedThatWay() {
		createFlag(FLAG);
		enable(FLAG);
		ResponseEntity<Map> created = post(RULES, new TargetingRuleRequest("Half of pro", null,
				List.of(attributeClause("plan", ClauseOperator.IN, "pro")), null, Map.of("off", 50, "on", 50), null, "tester"));

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat((Map<String, Object>) data(created).get("rolloutWeights")).containsEntry("on", 50);
		assertThat(evaluate(FLAG, "u1", Map.of("plan", "pro")).get("reason")).isEqualTo("RULE_MATCH");
	}

	@Test
	void unrelatedEnvironmentSavesDoNotWipeTargetingRulesOrIndividualTargets() {
		createFlag(FLAG);
		post(RULES, ruleRequest("Pro", "on", attributeClause("plan", ClauseOperator.IN, "pro")));
		post("/api/v1/flags/" + FLAG + "/environments/PRODUCTION/individual-targets/bulk",
				new BulkTargetRequest("user", "on", BulkOperation.ADD, List.of("vip"), "tester"));

		restTemplate.patchForObject("/api/v1/flags/" + FLAG + "/environments/PRODUCTION",
				new EnvironmentConfigRequest(true, "off", List.of(new StrategyRequest(null, StrategyType.PERCENTAGE_ROLLOUT, List.of(), null, null, 100, "on")), "tester"),
				Map.class);

		Map<String, Object> targeting = data(get("/api/v1/flags/" + FLAG + "/environments/PRODUCTION/targeting"));
		assertThat((List<?>) targeting.get("rules")).hasSize(1);
		assertThat((List<?>) targeting.get("individualTargets")).hasSize(1);
		assertThat(evaluate(FLAG, "vip").get("reason")).isEqualTo("INDIVIDUAL_TARGET");
	}

	@Test
	void everyRuleWriteIsAuditedAndBroadcastsAndRulesAppearInFlagResponseAndBootstrap() {
		createFlag(FLAG);
		enable(FLAG);
		post(RULES, ruleRequest("Pro", "on", attributeClause("plan", ClauseOperator.IN, "pro")));

		List<Map<String, Object>> audit = (List<Map<String, Object>>) get("/api/v1/flags/" + FLAG + "/audit").getBody().get("data");
		assertThat(audit).anySatisfy(event -> assertThat((String) event.get("summary")).contains("Added targeting rule 'Pro'"));

		Map<String, Object> flag = data(get("/api/v1/flags/" + FLAG));
		Map<String, Object> production = (Map<String, Object>) ((Map<String, Object>) flag.get("environments")).get("PRODUCTION");
		assertThat((List<?>) production.get("rules")).hasSize(1);

		List<Map<String, Object>> bootstrap = (List<Map<String, Object>>) get("/api/v1/eval/PRODUCTION/bootstrap").getBody().get("data");
		Map<String, Object> bootstrapEnv = (Map<String, Object>) bootstrap.get(0).get("environment");
		assertThat((List<?>) bootstrapEnv.get("rules")).as("SDKs bootstrap rules and evaluate locally").hasSize(1);
	}

	@Test
	void anArchivedFlagRefusesRuleEditsButStillReportsItsTargeting() {
		createFlag(FLAG);
		post(RULES, ruleRequest("Pro", "on", attributeClause("plan", ClauseOperator.IN, "pro")));
		post("/api/v1/flags/" + FLAG + "/archive", new ActingRequest("tester"));

		ResponseEntity<Map> refused = post(RULES, ruleRequest("Late", "on", attributeClause("plan", ClauseOperator.IN, "pro")));

		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(refused)).isEqualTo("FLAG_ARCHIVED");
		assertThat(get("/api/v1/flags/" + FLAG + "/environments/PRODUCTION/targeting").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void aVariationThatARuleStillServesCannotBeRemoved() {
		createMultivariateFlag("multi-flag");
		post("/api/v1/flags/multi-flag/environments/PRODUCTION/rules", ruleRequest("Blue for pro", "blue", attributeClause("plan", ClauseOperator.IN, "pro")));

		// Three variations, so the "keep at least two" guard is not what stops this: only the rule is.
		ResponseEntity<Map> refused = delete("/api/v1/flags/multi-flag/variations/blue");
		ResponseEntity<Map> unreferenced = delete("/api/v1/flags/multi-flag/variations/green");

		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(unreferenced.getStatusCode()).as("green is served by nothing, so it can go").isEqualTo(HttpStatus.OK);
	}

	// ---- bulk individual targeting

	@Test
	void bulkIndividualTargetingHandlesThousandsOfKeysAndEvaluatesThemAll() {
		createFlag(FLAG);
		enable(FLAG);
		List<String> keys = IntStream.range(0, 5_000).mapToObj(index -> "user-" + index).toList();

		ResponseEntity<Map> response = post("/api/v1/flags/" + FLAG + "/environments/PRODUCTION/individual-targets/bulk",
				new BulkTargetRequest("user", "on", BulkOperation.ADD, keys, "tester"));

		Map<String, Object> summary = (Map<String, Object>) data(response).get("summary");
		assertThat(summary.get("changed")).isEqualTo(5_000);
		assertThat(evaluate(FLAG, "user-4999").get("reason")).isEqualTo("INDIVIDUAL_TARGET");
		assertThat(evaluate(FLAG, "user-5000").get("reason")).isEqualTo("FALLTHROUGH");

		ResponseEntity<Map> again = post("/api/v1/flags/" + FLAG + "/environments/PRODUCTION/individual-targets/bulk",
				new BulkTargetRequest("user", "on", BulkOperation.ADD, List.of("user-1", "user-2", "brand-new"), "tester"));
		Map<String, Object> againSummary = (Map<String, Object>) data(again).get("summary");
		assertThat(againSummary.get("changed")).isEqualTo(1);
		assertThat(againSummary.get("unchanged")).isEqualTo(2);
	}

	@Test
	void replacingIndividualTargetsAndClearingThemWorks() {
		createFlag(FLAG);
		enable(FLAG);
		String url = "/api/v1/flags/" + FLAG + "/environments/PRODUCTION/individual-targets";

		put(url, new IndividualTargetsRequest(List.of(new IndividualTargetRequest("user", "on", List.of("a", "b"))), "tester"));
		assertThat(evaluate(FLAG, "a").get("reason")).isEqualTo("INDIVIDUAL_TARGET");

		ResponseEntity<Map> conflicting = put(url, new IndividualTargetsRequest(List.of(
				new IndividualTargetRequest("user", "on", List.of("a")), new IndividualTargetRequest("user", "off", List.of("a"))), "tester"));
		assertThat(conflicting.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		put(url, new IndividualTargetsRequest(List.of(), "tester"));
		assertThat(evaluate(FLAG, "a").get("reason")).isEqualTo("FALLTHROUGH");
	}

	// ---- multiple context kinds and mobile

	@Test
	void evaluationAcceptsMultipleContextKindsAndRulesCombineThem() {
		createFlag(FLAG);
		enable(FLAG);
		post(RULES, ruleRequest("Pro user in beta org", "on", attributeClause("plan", ClauseOperator.IN, "pro"),
				new ClauseRequest("organization", "key", ClauseOperator.IN, List.of("beta-org"), false)));
		EvaluationRequest inBetaOrg = new EvaluationRequest("u1", Map.of("plan", "pro"), Map.of("organization", new ContextRequest("beta-org", Map.of())));
		EvaluationRequest inOtherOrg = new EvaluationRequest("u1", Map.of("plan", "pro"), Map.of("organization", new ContextRequest("other", Map.of())));

		assertThat(evaluateWith(inBetaOrg).get("reason")).isEqualTo("RULE_MATCH");
		assertThat(evaluateWith(inOtherOrg).get("reason")).isEqualTo("FALLTHROUGH");
		assertThat(evaluate(FLAG, "u1", Map.of("plan", "pro")).get("reason")).as("legacy two-field requests still work").isEqualTo("FALLTHROUGH");
	}

	@Test
	void mobileTargetingUsesTheApplicationRegistryForSupportedAndUnsupportedVersions() {
		createFlag(FLAG);
		enable(FLAG);
		post("/api/v1/mobile-applications", new MobileApplicationRequest("com.acme.app", "Acme app", "2.4.0", "tester"));
		ResponseEntity<Map> created = post(RULES, ruleRequest("Unsupported apps", "on",
				new ClauseRequest("ld_application", null, ClauseOperator.APP_VERSION_SUPPORTED, null, true)));

		assertThat(data(created).get("category")).isEqualTo("MOBILE");
		EvaluationRequest oldApp = mobileRequest("com.acme.app", "2.3.0");
		EvaluationRequest currentApp = mobileRequest("com.acme.app", "2.4.0");

		assertThat(evaluateWith(oldApp).get("reason")).isEqualTo("RULE_MATCH");
		assertThat(evaluateWith(currentApp).get("reason")).isEqualTo("FALLTHROUGH");

		patch("/api/v1/mobile-applications/com.acme.app", new MobileApplicationUpdateRequest("Acme app", "2.5.0", "tester"));

		assertThat(evaluateWith(currentApp).get("reason")).as("2.4.0 became unsupported without touching the flag").isEqualTo("RULE_MATCH");
	}

	@Test
	void mobileRulesCanTargetApplicationVersionAndDeviceAttributes() {
		createFlag(FLAG);
		enable(FLAG);
		post(RULES, ruleRequest("New Android", "on",
				new ClauseRequest("ld_application", "versionName", ClauseOperator.SEMVER_GREATER_THAN, List.of("3.0.0"), false),
				new ClauseRequest("ld_device", "os.name", ClauseOperator.IN, List.of("Android"), false)));
		EvaluationRequest match = new EvaluationRequest("u1", Map.of(), Map.of(
				"ld_application", new ContextRequest("com.acme.app", Map.of("versionName", "3.2.0")),
				"ld_device", new ContextRequest("d1", Map.of("os.name", "Android"))));
		EvaluationRequest wrongOs = new EvaluationRequest("u1", Map.of(), Map.of(
				"ld_application", new ContextRequest("com.acme.app", Map.of("versionName", "3.2.0")),
				"ld_device", new ContextRequest("d1", Map.of("os.name", "iOS"))));

		assertThat(evaluateWith(match).get("reason")).isEqualTo("RULE_MATCH");
		assertThat(evaluateWith(wrongOs).get("reason")).isEqualTo("FALLTHROUGH");
	}

	// ---- segment API

	@Test
	void segmentCrudSearchAndPerEnvironmentIsolation() {
		ResponseEntity<Map> created = post("/api/v1/segments/PRODUCTION", new SegmentCreateRequest("beta-testers", "Beta testers", "Early adopters",
				null, List.of("u1"), null, List.of(new SegmentRuleRequest(null, "Acme staff",
						List.of(new ClauseRequest("user", "email", ClauseOperator.ENDS_WITH, List.of("@acme.io"), false)))), "tester"));
		post("/api/v1/segments/PRODUCTION", new SegmentCreateRequest("enterprise", "Enterprise customers", null, null, null, null, null, "tester"));
		post("/api/v1/segments/STAGING", new SegmentCreateRequest("beta-testers", "Staging beta", null, null, null, null, null, "tester"));

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(data(created).get("createdAt")).isNotNull();
		assertThat(((List<?>) data(created).get("rules"))).hasSize(1);

		List<Map<String, Object>> production = list("/api/v1/segments/PRODUCTION");
		assertThat(production).extracting(segment -> segment.get("key")).containsExactly("beta-testers", "enterprise");
		assertThat(production.get(0).get("includedCount")).isEqualTo(1);
		assertThat(production.get(0).get("ruleCount")).isEqualTo(1);
		assertThat(production.get(0)).as("list view omits the member lists").doesNotContainKey("included");
		assertThat(list("/api/v1/segments/PRODUCTION?q=ENTERPRISE")).extracting(segment -> segment.get("key")).containsExactly("enterprise");
		assertThat(list("/api/v1/segments/STAGING")).hasSize(1);
		assertThat(data(get("/api/v1/segments/STAGING/beta-testers")).get("name")).isEqualTo("Staging beta");

		ResponseEntity<Map> updated = patch("/api/v1/segments/PRODUCTION/enterprise", new SegmentUpdateRequest("Enterprise accounts", "Paying", null, "tester"));
		assertThat(data(updated).get("name")).isEqualTo("Enterprise accounts");
	}

	@Test
	void segmentKeysAreUniquePerEnvironmentAndValidated() {
		post("/api/v1/segments/PRODUCTION", new SegmentCreateRequest("beta-testers", "Beta", null, null, null, null, null, "tester"));

		ResponseEntity<Map> duplicate = post("/api/v1/segments/PRODUCTION", new SegmentCreateRequest("beta-testers", "Again", null, null, null, null, null, "tester"));
		ResponseEntity<Map> invalid = post("/api/v1/segments/PRODUCTION", new SegmentCreateRequest("Bad Key", "", null, null, null, null, null, null));

		assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(duplicate)).isEqualTo("SEGMENT_KEY_EXISTS");
		assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Map<String, Object> details = (Map<String, Object>) ((Map<String, Object>) invalid.getBody().get("error")).get("details");
		assertThat((Map<String, Object>) details.get("fieldErrors")).containsKeys("key", "name", "changedBy");
	}

	@Test
	void theDatabaseItselfRefusesADuplicateSegmentKeyInAnEnvironment() {
		Segment first = new Segment();
		first.setEnvironment(EnvironmentKey.PRODUCTION);
		first.setKey("dup");
		first.setName("Dup");
		segmentRepository.save(first);

		Segment second = new Segment();
		second.setEnvironment(EnvironmentKey.PRODUCTION);
		second.setKey("dup");
		second.setName("Dup again");

		assertThatThrownBy(() -> segmentRepository.save(second)).isInstanceOf(DuplicateKeyException.class);

		Segment otherEnvironment = new Segment();
		otherEnvironment.setEnvironment(EnvironmentKey.STAGING);
		otherEnvironment.setKey("dup");
		otherEnvironment.setName("Dup in staging");
		segmentRepository.save(otherEnvironment);
	}

	@Test
	void aSegmentInUseCannotBeDeletedUntilNoRuleReferencesItAndUsageSaysWhere() {
		createFlag(FLAG);
		createSegment("beta-testers", "Beta testers", List.of("u1"));
		String ruleId = idOf(post(RULES, ruleRequest("Beta users", "on", segmentClause("beta-testers", false))));

		List<Map<String, Object>> usage = (List<Map<String, Object>>) get("/api/v1/segments/PRODUCTION/beta-testers/usage").getBody().get("data");
		assertThat(usage).hasSize(1);
		assertThat(usage.get(0)).containsEntry("flagKey", FLAG).containsEntry("ruleId", ruleId).containsEntry("ruleDescription", "Beta users");

		ResponseEntity<Map> blocked = delete("/api/v1/segments/PRODUCTION/beta-testers");
		assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(blocked)).isEqualTo("SEGMENT_IN_USE");
		assertThat(get("/api/v1/segments/PRODUCTION/beta-testers").getStatusCode()).isEqualTo(HttpStatus.OK);

		delete(RULES + "/" + ruleId);

		assertThat(delete("/api/v1/segments/PRODUCTION/beta-testers").getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(get("/api/v1/segments/PRODUCTION/beta-testers").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(targetingCacheService.segment(EnvironmentKey.PRODUCTION, "beta-testers")).isEmpty();
	}

	@Test
	void aRuleCannotReferenceASegmentThatDoesNotExistInThatEnvironment() {
		createFlag(FLAG);
		createSegment("beta-testers", "Beta testers", List.of("u1"));

		ResponseEntity<Map> refused = post("/api/v1/flags/" + FLAG + "/environments/STAGING/rules",
				ruleRequest("Beta users", "on", segmentClause("beta-testers", false)));

		assertThat(refused.getStatusCode()).as("beta-testers only exists in PRODUCTION").isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void segmentRulesMatchByAttributeWithoutAnyExplicitMembers() {
		createFlag(FLAG);
		enable(FLAG);
		post("/api/v1/segments/PRODUCTION", new SegmentCreateRequest("acme-staff", "Acme staff", null, null, null, null,
				List.of(new SegmentRuleRequest(null, "Acme email", List.of(new ClauseRequest("user", "email", ClauseOperator.ENDS_WITH, List.of("@acme.io"), false)))), "tester"));
		post(RULES, ruleRequest("Staff", "on", segmentClause("acme-staff", false)));

		assertThat(evaluate(FLAG, "u1", Map.of("email", "sam@acme.io")).get("reason")).isEqualTo("RULE_MATCH");
		assertThat(evaluate(FLAG, "u2", Map.of("email", "sam@other.io")).get("reason")).isEqualTo("FALLTHROUGH");
	}

	// ---- mobile application registry API and SDK sync feed

	@Test
	void mobileApplicationRegistryCrudAndValidation() {
		ResponseEntity<Map> created = post("/api/v1/mobile-applications", new MobileApplicationRequest("com.acme.app", "Acme app", "2.4.0", "tester"));
		ResponseEntity<Map> duplicate = post("/api/v1/mobile-applications", new MobileApplicationRequest("com.acme.app", "Acme app", "2.4.0", "tester"));
		ResponseEntity<Map> badVersion = post("/api/v1/mobile-applications", new MobileApplicationRequest("com.other", "Other", "banana", "tester"));

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(badVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(list("/api/v1/mobile-applications")).hasSize(1);
		assertThat(data(get("/api/v1/mobile-applications/com.acme.app")).get("minSupportedVersion")).isEqualTo("2.4.0");
		assertThat(delete("/api/v1/mobile-applications/com.acme.app").getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(get("/api/v1/mobile-applications/com.acme.app").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void theSdkSyncFeedServesSegmentsAndApplicationsFromMemoryAndTracksWrites() {
		createSegment("beta-testers", "Beta testers", List.of("u1"));
		post("/api/v1/segments/STAGING", new SegmentCreateRequest("staging-only", "Staging only", null, null, null, null, null, "tester"));
		post("/api/v1/mobile-applications", new MobileApplicationRequest("com.acme.app", "Acme app", "2.4.0", "tester"));

		List<Map<String, Object>> segments = list("/api/v1/eval/PRODUCTION/segments");
		assertThat(segments).extracting(segment -> segment.get("key")).containsExactly("beta-testers");
		assertThat((List<Object>) segments.get(0).get("included")).containsExactly("u1");
		assertThat(list("/api/v1/eval/applications")).extracting(app -> app.get("key")).containsExactly("com.acme.app");

		post("/api/v1/segments/PRODUCTION/beta-testers/members", new SegmentMembersRequest(MemberList.INCLUDED, BulkOperation.ADD, List.of("u2"), "tester"));

		assertThat((List<Object>) list("/api/v1/eval/PRODUCTION/segments").get(0).get("included")).containsExactly("u1", "u2");
	}

	// ---- streaming

	@Test
	void sdkStreamsHearAboutSegmentApplicationAndRuleChangesForTheirEnvironment() throws Exception {
		createFlag(FLAG);
		List<String> lines = new CopyOnWriteArrayList<>();
		CompletableFuture<HttpResponse<Void>> stream = subscribe("PRODUCTION", lines);
		CompletableFuture<HttpResponse<Void>> otherEnvironment = subscribe("STAGING", new CopyOnWriteArrayList<>());
		Thread.sleep(400);

		createSegment("beta-testers", "Beta testers", List.of("u1"));
		post("/api/v1/mobile-applications", new MobileApplicationRequest("com.acme.app", "Acme app", "2.4.0", "tester"));
		post(RULES, ruleRequest("Pro", "on", attributeClause("plan", ClauseOperator.IN, "pro")));

		awaitLine(lines, "segment-changed");
		awaitLine(lines, "application-changed");
		awaitLine(lines, "flag-changed");
		stream.cancel(true);
		otherEnvironment.cancel(true);

		assertThat(lines).anySatisfy(line -> assertThat(line).contains("\"segmentKey\":\"beta-testers\"").contains("\"action\":\"CREATE\""));
		assertThat(lines).anySatisfy(line -> assertThat(line).contains("\"applicationKey\":\"com.acme.app\""));
	}

	@Test
	void aStreamForAnotherEnvironmentDoesNotHearProductionSegmentChanges() throws Exception {
		List<String> stagingLines = new CopyOnWriteArrayList<>();
		CompletableFuture<HttpResponse<Void>> staging = subscribe("STAGING", stagingLines);
		Thread.sleep(400);

		createSegment("beta-testers", "Beta testers", List.of("u1"));
		Thread.sleep(800);
		staging.cancel(true);

		assertThat(stagingLines).noneMatch(line -> line.contains("segment-changed"));
	}

	// ---- error handling

	@Test
	void malformedBodiesAndUnknownEnumValuesAreBadRequestsNotServerErrors() {
		createFlag(FLAG);

		ResponseEntity<Map> unknownOperator = restTemplate.exchange(RULES, HttpMethod.POST, new HttpEntity<>(Map.of("description", "x",
				"clauses", List.of(Map.of("attribute", "plan", "operator", "NOT_AN_OPERATOR", "values", List.of("a"))),
				"servedVariationKey", "on", "changedBy", "tester")), Map.class);
		ResponseEntity<Map> missingBody = restTemplate.exchange(RULES + "/some-id", HttpMethod.DELETE, null, Map.class);

		assertThat(unknownOperator.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(unknownOperator)).isEqualTo("MALFORMED_REQUEST");
		assertThat(missingBody.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void unknownFlagsRulesAndSegmentsAreNotFound() {
		createFlag(FLAG);

		assertThat(get("/api/v1/flags/ghost/environments/PRODUCTION/targeting").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(delete(RULES + "/ghost").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(patch(RULES + "/ghost", new RuleEnabledRequest(true, "tester")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(get("/api/v1/segments/PRODUCTION/ghost").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(post("/api/v1/segments/PRODUCTION/ghost/members",
				new SegmentMembersRequest(MemberList.INCLUDED, BulkOperation.ADD, List.of("u1"), "tester")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void validationFailuresReportTheOffendingFieldPaths() {
		createFlag(FLAG);

		ResponseEntity<Map> response = post(RULES, ruleRequest("Bad", "ghost",
				new ClauseRequest("user", "age", ClauseOperator.GREATER_THAN, List.of("old"), false),
				new ClauseRequest("user", "email", ClauseOperator.MATCHES, List.of("([bad"), false)));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Map<String, Object> details = (Map<String, Object>) ((Map<String, Object>) response.getBody().get("error")).get("details");
		assertThat((Map<String, Object>) details.get("fieldErrors")).containsKeys("servedVariationKey", "clauses[0].values", "clauses[1].values");
	}

	// ---- helpers

	private String rulesUrl(String flagKey) {
		return "/api/v1/flags/" + flagKey + "/environments/PRODUCTION/rules";
	}

	private void createFlag(String key) {
		restTemplate.postForEntity("/api/v1/flags", new FlagCreateRequest(key, key, "desc", FlagType.BOOLEAN, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(), "tester"), Map.class);
	}

	private void createMultivariateFlag(String key) {
		restTemplate.postForEntity("/api/v1/flags", new FlagCreateRequest(key, key, "desc", FlagType.MULTIVARIATE, "owner",
				List.of(new VariationRequest("red", "Red", "red"), new VariationRequest("blue", "Blue", "blue"),
						new VariationRequest("green", "Green", "green")), List.of(), "tester"), Map.class);
	}

	private void enable(String flagKey) {
		patchConfig(flagKey, "off");
	}

	private void patchConfig(String flagKey, String offVariation) {
		restTemplate.patchForObject("/api/v1/flags/" + flagKey + "/environments/PRODUCTION",
				new EnvironmentConfigRequest(true, offVariation, List.of(), "tester"), Map.class);
	}

	private void createSegment(String key, String name, List<String> included) {
		post("/api/v1/segments/PRODUCTION", new SegmentCreateRequest(key, name, null, null, included, null, null, "tester"));
	}

	private static TargetingRuleRequest ruleRequest(String description, String variation, ClauseRequest... clauses) {
		return new TargetingRuleRequest(description, null, List.of(clauses), variation, null, null, "tester");
	}

	private static ClauseRequest attributeClause(String attribute, ClauseOperator operator, String... values) {
		return new ClauseRequest("user", attribute, operator, List.of(values), false);
	}

	private static ClauseRequest segmentClause(String segmentKey, boolean negate) {
		return new ClauseRequest("user", null, ClauseOperator.IN_SEGMENT, List.of(segmentKey), negate);
	}

	private static EvaluationRequest mobileRequest(String appKey, String versionName) {
		return new EvaluationRequest("u1", Map.of(), Map.of("ld_application", new ContextRequest(appKey, Map.of("versionName", versionName))));
	}

	private Map<String, Object> evaluate(String flagKey, String userId) {
		return evaluate(flagKey, userId, Map.of());
	}

	private Map<String, Object> evaluate(String flagKey, String userId, Map<String, String> attributes) {
		return evaluateWith(flagKey, new EvaluationRequest(userId, attributes));
	}

	private Map<String, Object> evaluateWith(EvaluationRequest request) {
		return evaluateWith(FLAG, request);
	}

	private Map<String, Object> evaluateWith(String flagKey, EvaluationRequest request) {
		return data(restTemplate.postForEntity("/api/v1/eval/PRODUCTION/" + flagKey, request, Map.class));
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

	private ResponseEntity<Map> get(String url) {
		return restTemplate.getForEntity(url, Map.class);
	}

	private ResponseEntity<Map> delete(String url) {
		return restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(new ActingRequest("tester")), Map.class);
	}

	private List<Map<String, Object>> list(String url) {
		return (List<Map<String, Object>>) get(url).getBody().get("data");
	}

	private static Map<String, Object> data(ResponseEntity<Map> response) {
		return (Map<String, Object>) response.getBody().get("data");
	}

	private static String idOf(ResponseEntity<Map> response) {
		return (String) data(response).get("id");
	}

	private static List<String> ruleIds(ResponseEntity<Map> response) {
		List<Map<String, Object>> rules = (List<Map<String, Object>>) data(response).get("rules");
		List<String> ids = new ArrayList<>();
		rules.forEach(rule -> ids.add((String) rule.get("id")));

		return ids;
	}

	private static String errorCode(ResponseEntity<Map> response) {
		return (String) ((Map<String, Object>) response.getBody().get("error")).get("code");
	}

	private CompletableFuture<HttpResponse<Void>> subscribe(String environment, List<String> lines) {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/v1/stream/" + environment))
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

	private void awaitLine(List<String> lines, String needle) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);

		while (System.currentTimeMillis() < deadline) {
			if (lines.stream().anyMatch(line -> line.contains(needle))) {
				return;
			}

			Thread.sleep(100);
		}

		assertThat(lines).as("expected a '" + needle + "' event within 5s").anyMatch(line -> line.contains(needle));
	}
}
