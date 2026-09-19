package com.featureflag.flags;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.flags.audit.AuditEvent;
import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.FlagUpdateRequest;
import com.featureflag.flags.dto.VariationRequest;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@SuppressWarnings({ "unchecked", "rawtypes" })
class FlagPagingControllerIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private FlagRepository flagRepository;

	@Autowired
	private AuditRepository auditRepository;

	@Autowired
	private FlagCacheService flagCacheService;

	@Autowired
	private MongoTemplate mongoTemplate;

	@BeforeEach
	@AfterEach
	void cleanDatabase() {
		flagRepository.deleteAll();
		auditRepository.deleteAll();
		flagCacheService.load();
	}

	// ---- flag list: paging

	@Test
	void theListDefaultsToPageOneWithFiftyPerPageAndReportsMeta() {
		createFlags(3);

		ResponseEntity<Map> response = get("/api/v1/flags");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(keys(response)).containsExactly("flag-1", "flag-2", "flag-3");
		assertThat(meta(response)).containsEntry("page", 1).containsEntry("limit", 50).containsEntry("total", 3);
	}

	@Test
	void pagesAreDisjointAndCoverEveryFlagInOrderWithTheTotalOnEveryPage() {
		createFlags(5);

		ResponseEntity<Map> first = get("/api/v1/flags?page=1&limit=2");
		ResponseEntity<Map> middle = get("/api/v1/flags?page=2&limit=2");
		ResponseEntity<Map> last = get("/api/v1/flags?page=3&limit=2");

		assertThat(keys(first)).containsExactly("flag-1", "flag-2");
		assertThat(keys(middle)).containsExactly("flag-3", "flag-4");
		assertThat(keys(last)).containsExactly("flag-5");
		assertThat(meta(first)).containsEntry("page", 1).containsEntry("limit", 2).containsEntry("total", 5);
		assertThat(meta(middle)).containsEntry("page", 2).containsEntry("limit", 2).containsEntry("total", 5);
		assertThat(meta(last)).containsEntry("page", 3).containsEntry("limit", 2).containsEntry("total", 5);
	}

	@Test
	void aPageBeyondTheLastIsEmptyWithTheRealTotal() {
		createFlags(5);

		ResponseEntity<Map> response = get("/api/v1/flags?page=4&limit=2");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(keys(response)).isEmpty();
		assertThat(meta(response)).containsEntry("page", 4).containsEntry("limit", 2).containsEntry("total", 5);
	}

	@Test
	void anEmptyCollectionIsAnEmptyFirstPage() {
		ResponseEntity<Map> response = get("/api/v1/flags");

		assertThat(keys(response)).isEmpty();
		assertThat(meta(response)).containsEntry("page", 1).containsEntry("limit", 50).containsEntry("total", 0);
	}

	@Test
	void aLimitOfOneReturnsOneFlagPerPage() {
		createFlags(3);

		ResponseEntity<Map> response = get("/api/v1/flags?limit=1&page=2");

		assertThat(keys(response)).containsExactly("flag-2");
		assertThat(meta(response)).containsEntry("limit", 1).containsEntry("total", 3);
	}

	@Test
	void aLimitOfTwoHundredIsAcceptedAndTwoHundredAndOneIsRejected() {
		createFlags(2);

		ResponseEntity<Map> accepted = get("/api/v1/flags?limit=200");
		ResponseEntity<Map> rejected = get("/api/v1/flags?limit=201");

		assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(meta(accepted)).containsEntry("limit", 200);
		assertValidationErrorOn(rejected, "limit");
	}

	@Test
	void invalidPageAndLimitValuesAreValidationErrorsOnTheirFields() {
		assertValidationErrorOn(get("/api/v1/flags?page=0"), "page");
		assertValidationErrorOn(get("/api/v1/flags?page=-3"), "page");
		assertValidationErrorOn(get("/api/v1/flags?page=abc"), "page");
		assertValidationErrorOn(get("/api/v1/flags?limit=0"), "limit");
		assertValidationErrorOn(get("/api/v1/flags?limit=many"), "limit");
		assertValidationErrorOn(get("/api/v1/flags?page=0&limit=500"), "page", "limit");
	}

	@Test
	void flagsComeOldestFirstNotAlphabeticallyAndTiesFallBackToTheKey() throws InterruptedException {
		createFlag("zz-first", "Zed", "owner");
		Thread.sleep(5);
		createFlag("aa-second", "Aye", "owner");

		assertThat(keys(get("/api/v1/flags"))).containsExactly("zz-first", "aa-second");
	}

	// ---- flag list: filter, search and totals

	@Test
	void theLifecycleFilterAndPagingCombineAndTheTotalCountsOnlyTheFilteredFlags() {
		createFlags(5);
		archive("flag-2");
		archive("flag-4");

		ResponseEntity<Map> activePage = get("/api/v1/flags?lifecycle=ACTIVE&limit=2&page=2");
		ResponseEntity<Map> archivedPage = get("/api/v1/flags?lifecycle=ARCHIVED&limit=1&page=2");

		assertThat(keys(activePage)).containsExactly("flag-5");
		assertThat(meta(activePage)).containsEntry("total", 3);
		assertThat(keys(archivedPage)).containsExactly("flag-4");
		assertThat(meta(archivedPage)).containsEntry("total", 2);
	}

	@Test
	void totalsFollowCreatingAndArchivingFlags() {
		createFlags(3);
		assertThat(meta(get("/api/v1/flags"))).containsEntry("total", 3);
		assertThat(meta(get("/api/v1/flags?lifecycle=ARCHIVED"))).containsEntry("total", 0);

		createFlag("flag-4", "Flag 4", "owner");
		archive("flag-1");

		assertThat(meta(get("/api/v1/flags"))).containsEntry("total", 4);
		assertThat(meta(get("/api/v1/flags?lifecycle=ACTIVE"))).containsEntry("total", 3);
		assertThat(meta(get("/api/v1/flags?lifecycle=ARCHIVED"))).containsEntry("total", 1);
	}

	@Test
	void searchMatchesKeyNameAndOwnerCaseInsensitivelyButNotTheDescription() {
		createFlag("checkout-v2", "Payments", "alice");
		createFlag("billing", "Invoice CHECKOUT page", "bob");
		createFlag("search-box", "Search", "Checkout Team");
		createFlag("unrelated", "Unrelated", "carol");

		assertThat(keys(get("/api/v1/flags?q=CheckOut"))).containsExactly("checkout-v2", "billing", "search-box");
		assertThat(keys(get("/api/v1/flags?q=alice"))).containsExactly("checkout-v2");
		assertThat(keys(get("/api/v1/flags?q=desc"))).as("the description is not searched").isEmpty();
		assertThat(keys(get("/api/v1/flags?q="))).hasSize(4);
		assertThat(keys(search("  "))).as("blank is no filter").hasSize(4);
	}

	@Test
	void searchTextIsLiteralSoRegexCharactersDoNotMatchOrBreakTheQuery() {
		createFlag("dot-flag", "Version 1.2", "owner");
		createFlag("plain-flag", "Version 1x2", "owner");
		createFlag("paren-flag", "Plan (beta) [x]", "owner");
		createFlag("plus-flag", "C++ and $ money", "owner");
		createFlag("slash-flag", "Path a\\b", "owner");

		assertThat(keys(search("1.2"))).containsExactly("dot-flag");
		assertThat(keys(search(".*"))).isEmpty();
		assertThat(keys(search("(beta)"))).containsExactly("paren-flag");
		assertThat(keys(search("[x]"))).containsExactly("paren-flag");
		assertThat(keys(search("("))).as("an unbalanced group must not fail").containsExactly("paren-flag");
		assertThat(keys(search("C++"))).containsExactly("plus-flag");
		assertThat(keys(search("$ m"))).containsExactly("plus-flag");
		assertThat(keys(search("a\\b"))).containsExactly("slash-flag");
		assertThat(keys(search("\\E.*"))).as("a quote terminator must not escape the quoting").isEmpty();
		assertThat(keys(search("^Version"))).isEmpty();
	}

	@Test
	void searchLifecycleAndPagingCombineWithTheTotalCountingOnlyMatches() {
		for (int index = 1; index <= 5; index++) {
			createFlag("match-" + index, "Match " + index, "owner");
		}

		createFlag("other", "Other", "owner");
		archive("match-2");
		archive("other");

		ResponseEntity<Map> response = get("/api/v1/flags?q=match&lifecycle=ACTIVE&limit=2&page=2");

		assertThat(keys(response)).containsExactly("match-4", "match-5");
		assertThat(meta(response)).containsEntry("page", 2).containsEntry("limit", 2).containsEntry("total", 4);
		assertThat(keys(get("/api/v1/flags?q=match&lifecycle=ACTIVE&limit=2&page=3"))).isEmpty();
	}

	// ---- audit history

	@Test
	void auditHistoryIsNewestFirstEvenWhenEventsShareAMillisecond() {
		createFlag("audited", "Audited", "owner");

		for (int index = 1; index <= 4; index++) {
			update("audited", "editor-" + index);
		}

		ResponseEntity<Map> response = get("/api/v1/flags/audited/audit");

		assertThat(changedBy(response)).containsExactly("editor-4", "editor-3", "editor-2", "editor-1", "tester");
		assertThat(meta(response)).containsEntry("page", 1).containsEntry("limit", 50).containsEntry("total", 5);
	}

	@Test
	void auditHistoryPagesThroughEveryEventOnce() {
		createFlag("audited", "Audited", "owner");

		for (int index = 1; index <= 4; index++) {
			update("audited", "editor-" + index);
		}

		ResponseEntity<Map> first = get("/api/v1/flags/audited/audit?limit=2");
		ResponseEntity<Map> middle = get("/api/v1/flags/audited/audit?limit=2&page=2");
		ResponseEntity<Map> last = get("/api/v1/flags/audited/audit?limit=2&page=3");
		ResponseEntity<Map> beyond = get("/api/v1/flags/audited/audit?limit=2&page=4");

		assertThat(changedBy(first)).containsExactly("editor-4", "editor-3");
		assertThat(changedBy(middle)).containsExactly("editor-2", "editor-1");
		assertThat(changedBy(last)).containsExactly("tester");
		assertThat(changedBy(beyond)).isEmpty();
		assertThat(meta(beyond)).containsEntry("page", 4).containsEntry("limit", 2).containsEntry("total", 5);
		assertThat(meta(first)).containsEntry("total", 5);
	}

	@Test
	void auditHistoryOnlyCountsTheRequestedFlagAndValidatesPageAndLimit() {
		createFlag("audited", "Audited", "owner");
		createFlag("other", "Other", "owner");
		update("other", "someone");

		assertThat(meta(get("/api/v1/flags/audited/audit"))).containsEntry("total", 1);
		assertValidationErrorOn(get("/api/v1/flags/audited/audit?page=0"), "page");
		assertValidationErrorOn(get("/api/v1/flags/audited/audit?limit=201"), "limit");
		assertValidationErrorOn(get("/api/v1/flags/audited/audit?limit=x&page=y"), "page", "limit");
	}

	@Test
	void auditHistoryOfAnUnknownFlagIsNotFoundAndABadPageIsStillAValidationError() {
		ResponseEntity<Map> missing = get("/api/v1/flags/ghost/audit");

		assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(((Map<String, Object>) missing.getBody().get("error")).get("code")).isEqualTo("FLAG_NOT_FOUND");
		assertValidationErrorOn(get("/api/v1/flags/ghost/audit?page=0"), "page");
	}

	@Test
	void auditEventsOfDifferentActionsKeepTheirOrderAcrossArchiveAndRestore() {
		createFlag("audited", "Audited", "owner");
		archive("audited");
		restTemplate.postForEntity("/api/v1/flags/audited/restore", new ActingRequest("tester"), Map.class);

		List<Map<String, Object>> events = (List<Map<String, Object>>) get("/api/v1/flags/audited/audit?limit=2").getBody().get("data");

		assertThat(events).extracting(event -> event.get("action")).containsExactly("RESTORE", "ARCHIVE");
	}

	// ---- indexes

	@Test
	void theIndexesBehindThePagedQueriesExist() {
		assertThat(indexKeys(Flag.class)).contains(List.of("lifecycle", "createdAt", "key"), List.of("createdAt", "key"), List.of("key"));
		assertThat(indexKeys(AuditEvent.class)).contains(List.of("flagKey", "changedAt", "_id"));
	}

	// ---- helpers

	private List<List<String>> indexKeys(Class<?> type) {
		List<List<String>> keys = new ArrayList<>();

		for (IndexInfo info : mongoTemplate.indexOps(type).getIndexInfo()) {
			keys.add(info.getIndexFields().stream().map(field -> field.getKey()).toList());
		}

		return keys;
	}

	private void createFlags(int count) {
		IntStream.rangeClosed(1, count).forEach(index -> createFlag("flag-" + index, "Flag " + index, "owner"));
	}

	private void createFlag(String key, String name, String owner) {
		FlagCreateRequest request = new FlagCreateRequest(key, name, "desc", FlagType.BOOLEAN, owner,
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(),
				"tester");

		assertThat(restTemplate.postForEntity("/api/v1/flags", request, Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private void archive(String key) {
		restTemplate.postForEntity("/api/v1/flags/" + key + "/archive", new ActingRequest("tester"), Map.class);
	}

	private void update(String key, String changedBy) {
		restTemplate.exchange("/api/v1/flags/" + key, HttpMethod.PATCH,
				new HttpEntity<>(new FlagUpdateRequest("Audited", "desc", "owner", List.of(), changedBy)), Map.class);
	}

	private ResponseEntity<Map> get(String url) {
		return restTemplate.getForEntity(url, Map.class);
	}

	private ResponseEntity<Map> search(String text) {
		return restTemplate.getForEntity(URI.create(restTemplate.getRootUri() + "/api/v1/flags?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8)),
				Map.class);
	}

	private static List<String> keys(ResponseEntity<Map> response) {
		return ((List<Map<String, Object>>) response.getBody().get("data")).stream().map(flag -> (String) flag.get("key")).toList();
	}

	private static List<String> changedBy(ResponseEntity<Map> response) {
		return ((List<Map<String, Object>>) response.getBody().get("data")).stream().map(event -> (String) event.get("changedBy")).toList();
	}

	private static Map<String, Object> meta(ResponseEntity<Map> response) {
		return (Map<String, Object>) response.getBody().get("meta");
	}

	private static void assertValidationErrorOn(ResponseEntity<Map> response, String... fields) {
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
		assertThat(error.get("code")).isEqualTo("VALIDATION_ERROR");
		Map<String, Object> details = (Map<String, Object>) error.get("details");
		assertThat((Map<String, Object>) details.get("fieldErrors")).containsOnlyKeys(fields);
	}
}
