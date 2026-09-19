package com.featureflag.flags.targeting;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.targeting.dto.MobileApplicationRequest;
import com.featureflag.flags.targeting.dto.MobileApplicationUpdateRequest;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@SuppressWarnings({ "unchecked", "rawtypes" })
class MobileApplicationControllerIT {

	private static final String APPLICATIONS = "/api/v1/mobile-applications";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private MobileApplicationRepository applicationRepository;

	@Autowired
	private TargetingCacheService targetingCacheService;

	@BeforeEach
	@AfterEach
	void cleanDatabase() {
		applicationRepository.deleteAll();
		targetingCacheService.load();
	}

	// ---- create

	@Test
	void createReturns201WithTheTrimmedApplicationAndPersistsIt() {
		ResponseEntity<Map> response = post(APPLICATIONS, new MobileApplicationRequest("com.acme.app", " Acme app ", " 2.4.0 ", "tester"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Map<String, Object> created = data(response);
		assertThat(created).containsEntry("key", "com.acme.app").containsEntry("name", "Acme app")
				.containsEntry("minSupportedVersion", "2.4.0").containsEntry("updatedBy", "tester");
		assertThat(created.get("createdAt")).isNotNull();
		assertThat(applicationRepository.existsByKey("com.acme.app")).isTrue();
	}

	@Test
	void createRejectsEveryInvalidFieldTogetherWithAValidationError() {
		ResponseEntity<Map> response = post(APPLICATIONS, new MobileApplicationRequest("has space", " ", "banana", null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
		assertThat(fieldErrors(response)).containsOnlyKeys("key", "name", "minSupportedVersion", "changedBy");
		assertThat(applicationRepository.count()).isZero();
	}

	@Test
	void createRejectsAMissingKeyAndAMissingVersion() {
		ResponseEntity<Map> response = post(APPLICATIONS, new MobileApplicationRequest(null, "Name", null, "tester"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(response)).containsOnlyKeys("key", "minSupportedVersion");
	}

	@Test
	void createRejectsAMalformedBodyAs400() {
		ResponseEntity<Map> response = restTemplate.exchange(APPLICATIONS, HttpMethod.POST, new HttpEntity<>("{not json", jsonHeaders()), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo("MALFORMED_REQUEST");
	}

	@Test
	void creatingTheSameKeyTwiceIsAConflictAndKeepsTheFirstApplication() {
		createApplication("com.acme.app", "Acme", "2.0.0");

		ResponseEntity<Map> duplicate = post(APPLICATIONS, new MobileApplicationRequest("com.acme.app", "Other", "9.0.0", "tester"));

		assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(errorCode(duplicate)).isEqualTo("APPLICATION_KEY_EXISTS");
		assertThat(data(get(APPLICATIONS + "/com.acme.app"))).containsEntry("name", "Acme").containsEntry("minSupportedVersion", "2.0.0");
		assertThat(applicationRepository.count()).isEqualTo(1);
	}

	// ---- get one

	@Test
	void getReturnsTheApplicationByKey() {
		createApplication("com.acme.app", "Acme", "2.0.0");

		ResponseEntity<Map> response = get(APPLICATIONS + "/com.acme.app");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(data(response)).containsEntry("key", "com.acme.app").containsEntry("name", "Acme")
				.containsEntry("minSupportedVersion", "2.0.0");
	}

	@Test
	void getOfAnUnknownKeyIs404() {
		ResponseEntity<Map> response = get(APPLICATIONS + "/ghost");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(response)).isEqualTo("APPLICATION_NOT_FOUND");
	}

	// ---- list and paging

	@Test
	void listIsSortedByNameCaseInsensitivelyWithTheKeyAsTieBreakAndCarriesMeta() {
		createApplication("app-c", "beta app", "1.0.0");
		createApplication("app-b", "Alpha app", "1.0.0");
		createApplication("app-a", "Alpha app", "1.0.0");
		createApplication("app-d", "Zeta app", "1.0.0");

		ResponseEntity<Map> response = get(APPLICATIONS);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(keys(response)).containsExactly("app-a", "app-b", "app-c", "app-d");
		assertThat(meta(response)).containsEntry("page", 1).containsEntry("limit", 50).containsEntry("total", 4);
	}

	@Test
	void listPagesAreDisjointAndABeyondLastPageIsEmptyWithTheRealTotal() {
		IntStream.rangeClosed(1, 5).forEach(index -> createApplication("app-" + index, "App " + index, "1.0.0"));

		ResponseEntity<Map> first = get(APPLICATIONS + "?page=1&limit=2");
		ResponseEntity<Map> middle = get(APPLICATIONS + "?page=2&limit=2");
		ResponseEntity<Map> last = get(APPLICATIONS + "?page=3&limit=2");
		ResponseEntity<Map> beyond = get(APPLICATIONS + "?page=4&limit=2");

		assertThat(keys(first)).containsExactly("app-1", "app-2");
		assertThat(keys(middle)).containsExactly("app-3", "app-4");
		assertThat(keys(last)).containsExactly("app-5");
		assertThat(keys(beyond)).isEmpty();
		assertThat(meta(first)).containsEntry("page", 1).containsEntry("limit", 2).containsEntry("total", 5);
		assertThat(meta(beyond)).containsEntry("page", 4).containsEntry("limit", 2).containsEntry("total", 5);
	}

	@Test
	void listAcceptsLimitOneAndTwoHundredAndRejectsTwoHundredAndOne() {
		createApplication("app-1", "App 1", "1.0.0");
		createApplication("app-2", "App 2", "1.0.0");

		assertThat(keys(get(APPLICATIONS + "?limit=1&page=2"))).containsExactly("app-2");
		assertThat(meta(get(APPLICATIONS + "?limit=200"))).containsEntry("limit", 200);
		assertValidationErrorOn(get(APPLICATIONS + "?limit=201"), "limit");
	}

	@Test
	void listRejectsInvalidPageAndLimitValues() {
		assertValidationErrorOn(get(APPLICATIONS + "?page=0"), "page");
		assertValidationErrorOn(get(APPLICATIONS + "?page=abc"), "page");
		assertValidationErrorOn(get(APPLICATIONS + "?limit=0"), "limit");
		assertValidationErrorOn(get(APPLICATIONS + "?limit=lots"), "limit");
		assertValidationErrorOn(get(APPLICATIONS + "?page=-1&limit=999"), "page", "limit");
	}

	@Test
	void theListTotalFollowsCreateAndDelete() {
		createApplication("app-1", "App 1", "1.0.0");
		createApplication("app-2", "App 2", "1.0.0");
		assertThat(meta(get(APPLICATIONS))).containsEntry("total", 2);

		delete(APPLICATIONS + "/app-1", "tester");

		assertThat(meta(get(APPLICATIONS))).containsEntry("total", 1);
		assertThat(keys(get(APPLICATIONS))).containsExactly("app-2");
	}

	// ---- update

	@Test
	void patchChangesTheNameAndMinimumVersionButNotTheKey() {
		createApplication("com.acme.app", "Acme", "2.0.0");

		ResponseEntity<Map> response = patch(APPLICATIONS + "/com.acme.app", new MobileApplicationUpdateRequest(" Acme mobile ", "2.5.0", "editor"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(data(response)).containsEntry("key", "com.acme.app").containsEntry("name", "Acme mobile")
				.containsEntry("minSupportedVersion", "2.5.0").containsEntry("updatedBy", "editor");
		assertThat(data(get(APPLICATIONS + "/com.acme.app"))).containsEntry("minSupportedVersion", "2.5.0");
	}

	@Test
	void patchRejectsInvalidFieldsAndLeavesTheApplicationUnchanged() {
		createApplication("com.acme.app", "Acme", "2.0.0");

		ResponseEntity<Map> response = patch(APPLICATIONS + "/com.acme.app", new MobileApplicationUpdateRequest(" ", "nope", null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
		assertThat(fieldErrors(response)).containsOnlyKeys("name", "minSupportedVersion", "changedBy");
		assertThat(data(get(APPLICATIONS + "/com.acme.app"))).containsEntry("name", "Acme").containsEntry("minSupportedVersion", "2.0.0");
	}

	@Test
	void patchOfAnUnknownKeyIs404() {
		ResponseEntity<Map> response = patch(APPLICATIONS + "/ghost", new MobileApplicationUpdateRequest("Ghost", "1.0.0", "tester"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(response)).isEqualTo("APPLICATION_NOT_FOUND");
	}

	// ---- delete

	@Test
	void deleteRemovesTheApplicationAndItThenReturns404() {
		createApplication("com.acme.app", "Acme", "2.0.0");

		ResponseEntity<Map> response = delete(APPLICATIONS + "/com.acme.app", "tester");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(data(response)).containsEntry("deleted", "com.acme.app");
		assertThat(get(APPLICATIONS + "/com.acme.app").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(applicationRepository.existsByKey("com.acme.app")).isFalse();
	}

	@Test
	void deleteRequiresChangedByAndKeepsTheApplicationOtherwise() {
		createApplication("com.acme.app", "Acme", "2.0.0");

		ResponseEntity<Map> response = delete(APPLICATIONS + "/com.acme.app", " ");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(response)).containsOnlyKeys("changedBy");
		assertThat(applicationRepository.existsByKey("com.acme.app")).isTrue();
	}

	@Test
	void deleteOfAnUnknownKeyIs404() {
		ResponseEntity<Map> response = delete(APPLICATIONS + "/ghost", "tester");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(errorCode(response)).isEqualTo("APPLICATION_NOT_FOUND");
	}

	// ---- helpers

	private void createApplication(String key, String name, String minSupportedVersion) {
		ResponseEntity<Map> response = post(APPLICATIONS, new MobileApplicationRequest(key, name, minSupportedVersion, "tester"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private static HttpHeaders jsonHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		return headers;
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

	private static Map<String, Object> data(ResponseEntity<Map> response) {
		return (Map<String, Object>) response.getBody().get("data");
	}

	private static Map<String, Object> meta(ResponseEntity<Map> response) {
		return (Map<String, Object>) response.getBody().get("meta");
	}

	private static List<String> keys(ResponseEntity<Map> response) {
		return ((List<Map<String, Object>>) response.getBody().get("data")).stream().map(application -> (String) application.get("key")).toList();
	}

	private static String errorCode(ResponseEntity<Map> response) {
		return (String) ((Map<String, Object>) response.getBody().get("error")).get("code");
	}

	private static Map<String, Object> fieldErrors(ResponseEntity<Map> response) {
		Map<String, Object> details = (Map<String, Object>) ((Map<String, Object>) response.getBody().get("error")).get("details");

		return (Map<String, Object>) details.get("fieldErrors");
	}

	private static void assertValidationErrorOn(ResponseEntity<Map> response, String... fields) {
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
		assertThat(fieldErrors(response)).containsOnlyKeys(fields);
	}
}
