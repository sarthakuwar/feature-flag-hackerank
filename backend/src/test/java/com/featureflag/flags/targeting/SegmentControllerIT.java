package com.featureflag.flags.targeting;

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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.FlagRepository;
import com.featureflag.flags.FlagType;
import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.targeting.dto.ClauseRequest;
import com.featureflag.flags.targeting.dto.SegmentCreateRequest;
import com.featureflag.flags.targeting.dto.SegmentMembersRequest;
import com.featureflag.flags.targeting.dto.SegmentRuleRequest;
import com.featureflag.flags.targeting.dto.SegmentUpdateRequest;
import com.featureflag.flags.targeting.dto.TargetingRuleRequest;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@SuppressWarnings({ "unchecked", "rawtypes" })
class SegmentControllerIT {

	private static final String SEGMENTS = "/api/v1/segments/PRODUCTION";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private SegmentRepository segmentRepository;

	@Autowired
	private FlagRepository flagRepository;

	@Autowired
	private AuditRepository auditRepository;

	@Autowired
	private FlagCacheService flagCacheService;

	@Autowired
	private TargetingCacheService targetingCacheService;

	@BeforeEach
	@AfterEach
	void cleanDatabase() {
		segmentRepository.deleteAll();
		flagRepository.deleteAll();
		auditRepository.deleteAll();
		flagCacheService.load();
		targetingCacheService.load();
	}

	// ---- create

	@Test
	void createReturns201WithTheFullSegmentAndDefaultsTheContextKindToUser() {
		ResponseEntity<Map> response = post(SEGMENTS, new SegmentCreateRequest("beta-testers", " Beta testers ", "Early adopters", null,
				List.of("u1", " u2 ", "u1"), List.of("u9"), List.of(emailRule()), "tester"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Map<String, Object> created = data(response);
		assertThat(created).containsEntry("key", "beta-testers").containsEntry("name", "Beta testers")
				.containsEntry("description", "Early adopters").containsEntry("contextKind", "user")
				.containsEntry("environment", "PRODUCTION").containsEntry("updatedBy", "tester");
		assertThat((List<String>) created.get("included")).containsExactlyInAnyOrder("u1", "u2");
		assertThat((List<String>) created.get("excluded")).containsExactly("u9");
		assertThat((List<Map<String, Object>>) created.get("rules")).hasSize(1);
		assertThat(segmentRepository.findByEnvironmentAndKey(EnvironmentKey.PRODUCTION, "beta-testers")).isPresent();
	}

	@Test
	void createRejectsEveryInvalidFieldTogetherWithAValidationError() {
		ResponseEntity<Map> response = post(SEGMENTS, new SegmentCreateRequest("Bad Key", " ", null, "Not A Kind", null, null, null, null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
		assertThat(fieldErrors(response)).containsOnlyKeys("key", "name", "changedBy", "contextKind");
		assertThat(segmentRepository.count()).isZero();
	}

	@Test
	void createRejectsAKeyThatIsBothIncludedAndExcluded() {
		ResponseEntity<Map> response = post(SEGMENTS, new SegmentCreateRequest("beta-testers", "Beta", null, null, List.of("u1"), List.of("u1"), null,
				"tester"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(response)).containsOnlyKeys("excluded");
	}

	@Test
	void createRejectsAnInvalidRuleClause() {
		SegmentRuleRequest badRule = new SegmentRuleRequest(null, "bad", List.of(new ClauseRequest("user", "email", ClauseOperator.ENDS_WITH, List.of(), false)));

		ResponseEntity<Map> response = post(SEGMENTS, new SegmentCreateRequest("beta-testers", "Beta", null, null, null, null, List.of(badRule), "tester"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
		assertThat(fieldErrors(response).keySet()).anyMatch(field -> field.startsWith("rules[0]"));
	}

	@Test
	void createRejectsAMalformedBodyAs400() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map> response = restTemplate.exchange(SEGMENTS, HttpMethod.POST, new HttpEntity<>("{not json", headers), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo("MALFORMED_REQUEST");
	}

	@Test
	void creatingTheSameKeyTwiceInOneEnvironmentIsAConflictButAnotherEnvironmentIsFine() {
		createSegment("beta-testers", "Beta", List.of("u1"));

		ResponseEntity<Map> duplicate = post(SEGMENTS, new SegmentCreateRequest("beta-testers", "Other", null, null, null, null, null, "tester"));
		ResponseEntity<Map> otherEnvironment = post("/api/v1/segments/STAGING",
				new SegmentCreateRequest("beta-testers", "Beta staging", null, null, null, null, null, "tester"));

		assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(duplicate)).isEqualTo("SEGMENT_KEY_EXISTS");
		assertThat(data(get(SEGMENTS + "/beta-testers"))).containsEntry("name", "Beta");
		assertThat(otherEnvironment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	// ---- get one and list

	@Test
	void getReturnsTheSegmentWithItsMembers() {
		createSegment("beta-testers", "Beta", List.of("u1", "u2"));

		ResponseEntity<Map> response = get(SEGMENTS + "/beta-testers");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(data(response)).containsEntry("key", "beta-testers").containsEntry("name", "Beta");
		assertThat((List<String>) data(response).get("included")).containsExactlyInAnyOrder("u1", "u2");
	}

	@Test
	void getOfAnUnknownKeyOrTheSameKeyInAnotherEnvironmentIs404() {
		createSegment("beta-testers", "Beta", null);

		ResponseEntity<Map> unknown = get(SEGMENTS + "/ghost");
		ResponseEntity<Map> otherEnvironment = get("/api/v1/segments/STAGING/beta-testers");

		assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(unknown)).isEqualTo("SEGMENT_NOT_FOUND");
		assertThat(otherEnvironment.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void listReturnsSummariesWithCountsSortedByNameAndScopedToTheEnvironment() {
		createSegment("zeta", "zeta group", List.of("u1", "u2"));
		createSegment("alpha", "Alpha group", null);
		post("/api/v1/segments/STAGING", new SegmentCreateRequest("staging-only", "Staging only", null, null, null, null, null, "tester"));

		ResponseEntity<Map> response = get(SEGMENTS);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map<String, Object>> segments = (List<Map<String, Object>>) response.getBody().get("data");
		assertThat(segments).extracting(segment -> segment.get("key")).containsExactly("alpha", "zeta");
		assertThat(segments.get(1)).containsEntry("includedCount", 2).containsEntry("excludedCount", 0).containsEntry("ruleCount", 0);
		assertThat(segments.get(1)).doesNotContainKey("included");
		assertThat(response.getBody()).as("segments are not paginated").doesNotContainKey("meta");
	}

	@Test
	void listFiltersByTextAcrossKeyNameAndDescriptionAndByContextKind() {
		post(SEGMENTS, new SegmentCreateRequest("beta-testers", "Testers", "Early adopters", null, null, null, null, "tester"));
		post(SEGMENTS, new SegmentCreateRequest("big-orgs", "Big organizations", null, "organization", null, null, null, "tester"));

		assertThat(keys(get(SEGMENTS + "?q=BETA"))).containsExactly("beta-testers");
		assertThat(keys(get(SEGMENTS + "?q=adopters"))).containsExactly("beta-testers");
		assertThat(keys(get(SEGMENTS + "?q=organizations"))).containsExactly("big-orgs");
		assertThat(keys(get(SEGMENTS + "?contextKind=organization"))).containsExactly("big-orgs");
		assertThat(keys(get(SEGMENTS + "?q=zzz"))).isEmpty();
	}

	// ---- update

	@Test
	void patchChangesNameDescriptionAndRulesButNeverTheKeyOrMembers() {
		createSegment("beta-testers", "Beta", List.of("u1"));

		ResponseEntity<Map> response = patch(SEGMENTS + "/beta-testers", new SegmentUpdateRequest(" Beta group ", "New description", List.of(emailRule()), "editor"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<String, Object> updated = data(response);
		assertThat(updated).containsEntry("key", "beta-testers").containsEntry("name", "Beta group")
				.containsEntry("description", "New description").containsEntry("updatedBy", "editor");
		assertThat((List<String>) updated.get("included")).containsExactly("u1");
		assertThat((List<Map<String, Object>>) updated.get("rules")).hasSize(1);
	}

	@Test
	void patchWithoutRulesKeepsTheExistingRules() {
		post(SEGMENTS, new SegmentCreateRequest("beta-testers", "Beta", null, null, null, null, List.of(emailRule()), "tester"));

		ResponseEntity<Map> response = patch(SEGMENTS + "/beta-testers", new SegmentUpdateRequest("Beta", null, null, "tester"));

		assertThat((List<Map<String, Object>>) data(response).get("rules")).hasSize(1);
	}

	@Test
	void patchRejectsInvalidFieldsAndLeavesTheSegmentUnchanged() {
		createSegment("beta-testers", "Beta", null);

		ResponseEntity<Map> response = patch(SEGMENTS + "/beta-testers", new SegmentUpdateRequest(" ", null, null, null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(response)).containsOnlyKeys("name", "changedBy");
		assertThat(data(get(SEGMENTS + "/beta-testers"))).containsEntry("name", "Beta");
	}

	@Test
	void patchOfAnUnknownKeyIs404() {
		ResponseEntity<Map> response = patch(SEGMENTS + "/ghost", new SegmentUpdateRequest("Ghost", null, null, "tester"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(response)).isEqualTo("SEGMENT_NOT_FOUND");
	}

	// ---- members

	@Test
	void membersAddRemoveAndMoveKeysBetweenTheListsWithACountSummary() {
		createSegment("beta-testers", "Beta", List.of("u1"));

		ResponseEntity<Map> added = post(SEGMENTS + "/beta-testers/members",
				new SegmentMembersRequest(MemberList.INCLUDED, BulkOperation.ADD, List.of("u1", "u2", "u3"), "tester"));
		ResponseEntity<Map> moved = post(SEGMENTS + "/beta-testers/members",
				new SegmentMembersRequest(MemberList.EXCLUDED, BulkOperation.ADD, List.of("u3"), "tester"));
		ResponseEntity<Map> removed = post(SEGMENTS + "/beta-testers/members",
				new SegmentMembersRequest(MemberList.INCLUDED, BulkOperation.REMOVE, List.of("u1", "missing"), "tester"));

		assertThat(added.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat((Map<String, Object>) data(added).get("summary")).containsEntry("requested", 3).containsEntry("changed", 2).containsEntry("unchanged", 1);
		assertThat((Map<String, Object>) data(moved).get("summary")).containsEntry("changed", 1);
		assertThat((Map<String, Object>) data(removed).get("summary")).containsEntry("requested", 2).containsEntry("changed", 1).containsEntry("unchanged", 1);
		Map<String, Object> segment = data(get(SEGMENTS + "/beta-testers"));
		assertThat((List<String>) segment.get("included")).containsExactly("u2");
		assertThat((List<String>) segment.get("excluded")).containsExactly("u3");
	}

	@Test
	void membersRejectsMissingListOperationKeysAndChangedBy() {
		createSegment("beta-testers", "Beta", null);

		ResponseEntity<Map> response = post(SEGMENTS + "/beta-testers/members", new SegmentMembersRequest(null, null, List.of(), null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(response)).containsOnlyKeys("changedBy", "list", "operation", "keys");
	}

	@Test
	void membersOfAnUnknownSegmentIs404() {
		ResponseEntity<Map> response = post(SEGMENTS + "/ghost/members",
				new SegmentMembersRequest(MemberList.INCLUDED, BulkOperation.ADD, List.of("u1"), "tester"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(response)).isEqualTo("SEGMENT_NOT_FOUND");
	}

	// ---- usage and delete

	@Test
	void usageListsTheRulesOfActiveAndArchivedFlagsThatReferenceTheSegmentInThisEnvironmentOnly() {
		createSegment("beta-testers", "Beta", null);
		createSegment("other", "Other", null);
		createFlag("active-flag");
		createFlag("archived-flag");
		createFlag("unrelated-flag");
		String activeRule = addSegmentRule("active-flag", "PRODUCTION", "beta-testers");
		String archivedRule = addSegmentRule("archived-flag", "PRODUCTION", "beta-testers");
		addSegmentRule("unrelated-flag", "PRODUCTION", "other");
		post("/api/v1/flags/archived-flag/archive", new ActingRequest("tester"));

		ResponseEntity<Map> response = get(SEGMENTS + "/beta-testers/usage");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map<String, Object>> usage = (List<Map<String, Object>>) response.getBody().get("data");
		assertThat(usage).extracting(entry -> entry.get("flagKey")).containsExactly("active-flag", "archived-flag");
		assertThat(usage).extracting(entry -> entry.get("ruleId")).containsExactly(activeRule, archivedRule);
		assertThat(usage).extracting(entry -> entry.get("lifecycle")).containsExactly("ACTIVE", "ARCHIVED");
		assertThat(get("/api/v1/segments/STAGING/beta-testers/usage").getStatusCode()).as("unknown in staging").isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void usageOfAnUnusedSegmentIsEmptyAndOfAnUnknownSegmentIs404() {
		createSegment("beta-testers", "Beta", null);

		ResponseEntity<Map> unused = get(SEGMENTS + "/beta-testers/usage");
		ResponseEntity<Map> unknown = get(SEGMENTS + "/ghost/usage");

		assertThat(unused.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat((List<Object>) unused.getBody().get("data")).isEmpty();
		assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(unknown)).isEqualTo("SEGMENT_NOT_FOUND");
	}

	@Test
	void usageFollowsRuleChangesMadeAfterTheFlagWasCached() {
		createSegment("beta-testers", "Beta", null);
		createFlag("active-flag");
		String ruleId = addSegmentRule("active-flag", "PRODUCTION", "beta-testers");
		assertThat(list(get(SEGMENTS + "/beta-testers/usage"))).hasSize(1);

		restTemplate.exchange("/api/v1/flags/active-flag/environments/PRODUCTION/rules/" + ruleId, HttpMethod.DELETE,
				new HttpEntity<>(new ActingRequest("tester")), Map.class);

		assertThat(list(get(SEGMENTS + "/beta-testers/usage"))).isEmpty();
	}

	@Test
	void deleteRemovesTheSegmentAndItThenReturns404() {
		createSegment("beta-testers", "Beta", List.of("u1"));

		ResponseEntity<Map> response = delete(SEGMENTS + "/beta-testers", "tester");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(data(response)).containsEntry("deleted", "beta-testers");
		assertThat(get(SEGMENTS + "/beta-testers").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(segmentRepository.count()).isZero();
	}

	@Test
	void deleteIsBlockedWhileAFlagRuleStillReferencesTheSegmentEvenOnAnArchivedFlag() {
		createSegment("beta-testers", "Beta", null);
		createFlag("legacy-flag");
		String ruleId = addSegmentRule("legacy-flag", "PRODUCTION", "beta-testers");
		post("/api/v1/flags/legacy-flag/archive", new ActingRequest("tester"));

		ResponseEntity<Map> blocked = delete(SEGMENTS + "/beta-testers", "tester");

		assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(blocked)).isEqualTo("SEGMENT_IN_USE");
		Map<String, Object> details = (Map<String, Object>) ((Map<String, Object>) blocked.getBody().get("error")).get("details");
		List<Map<String, Object>> usedBy = (List<Map<String, Object>>) details.get("usedBy");
		assertThat(usedBy).hasSize(1);
		assertThat(usedBy.get(0)).containsEntry("flagKey", "legacy-flag").containsEntry("ruleId", ruleId).containsEntry("lifecycle", "ARCHIVED");
		assertThat(get(SEGMENTS + "/beta-testers").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void deleteSucceedsOnceTheReferencingRuleIsRemoved() {
		createSegment("beta-testers", "Beta", null);
		createFlag("flag-one");
		String ruleId = addSegmentRule("flag-one", "PRODUCTION", "beta-testers");
		assertThat(delete(SEGMENTS + "/beta-testers", "tester").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		restTemplate.exchange("/api/v1/flags/flag-one/environments/PRODUCTION/rules/" + ruleId, HttpMethod.DELETE,
				new HttpEntity<>(new ActingRequest("tester")), Map.class);

		assertThat(delete(SEGMENTS + "/beta-testers", "tester").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void aRuleInAnotherEnvironmentDoesNotBlockDeletingTheSegment() {
		createSegment("beta-testers", "Beta", null);
		post("/api/v1/segments/STAGING", new SegmentCreateRequest("beta-testers", "Beta staging", null, null, null, null, null, "tester"));
		createFlag("flag-one");
		addSegmentRule("flag-one", "STAGING", "beta-testers");

		assertThat(delete(SEGMENTS + "/beta-testers", "tester").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void deleteRequiresChangedByAndKeepsTheSegmentOtherwise() {
		createSegment("beta-testers", "Beta", null);

		ResponseEntity<Map> response = delete(SEGMENTS + "/beta-testers", " ");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(response)).containsOnlyKeys("changedBy");
		assertThat(segmentRepository.count()).isEqualTo(1);
	}

	@Test
	void deleteOfAnUnknownKeyIs404() {
		ResponseEntity<Map> response = delete(SEGMENTS + "/ghost", "tester");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(response)).isEqualTo("SEGMENT_NOT_FOUND");
	}

	// ---- helpers

	private static SegmentRuleRequest emailRule() {
		return new SegmentRuleRequest(null, "Acme staff", List.of(new ClauseRequest("user", "email", ClauseOperator.ENDS_WITH, List.of("@acme.io"), false)));
	}

	private void createSegment(String key, String name, List<String> included) {
		ResponseEntity<Map> response = post(SEGMENTS, new SegmentCreateRequest(key, name, null, null, included, null, null, "tester"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private void createFlag(String key) {
		restTemplate.postForEntity("/api/v1/flags", new FlagCreateRequest(key, key, "desc", FlagType.BOOLEAN, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(), "tester"), Map.class);
	}

	private String addSegmentRule(String flagKey, String environment, String segmentKey) {
		ClauseRequest clause = new ClauseRequest("user", null, ClauseOperator.IN_SEGMENT, List.of(segmentKey), false);
		ResponseEntity<Map> response = post("/api/v1/flags/" + flagKey + "/environments/" + environment + "/rules",
				new TargetingRuleRequest("uses " + segmentKey, null, List.of(clause), "on", null, null, "tester"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		return (String) data(response).get("id");
	}

	private ResponseEntity<Map> post(String url, Object body) {
		return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body), Map.class);
	}

	private ResponseEntity<Map> patch(String url, Object body) {
		return restTemplate.exchange(url, HttpMethod.PATCH, new HttpEntity<>(body), Map.class);
	}

	private ResponseEntity<Map> get(String url) {
		return restTemplate.getForEntity(url, Map.class);
	}

	private ResponseEntity<Map> delete(String url, String changedBy) {
		return restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(new ActingRequest(changedBy)), Map.class);
	}

	private static List<Object> list(ResponseEntity<Map> response) {
		return (List<Object>) response.getBody().get("data");
	}

	private static Map<String, Object> data(ResponseEntity<Map> response) {
		return (Map<String, Object>) response.getBody().get("data");
	}

	private static List<String> keys(ResponseEntity<Map> response) {
		return ((List<Map<String, Object>>) response.getBody().get("data")).stream().map(segment -> (String) segment.get("key")).toList();
	}

	private static String errorCode(ResponseEntity<Map> response) {
		return (String) ((Map<String, Object>) response.getBody().get("error")).get("code");
	}

	private static Map<String, Object> fieldErrors(ResponseEntity<Map> response) {
		Map<String, Object> details = (Map<String, Object>) ((Map<String, Object>) response.getBody().get("error")).get("details");

		return (Map<String, Object>) details.get("fieldErrors");
	}
}
