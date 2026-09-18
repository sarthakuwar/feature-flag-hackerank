package com.featureflag.flags;

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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.dto.BulkKeysRequest;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.FlagUpdateRequest;
import com.featureflag.flags.dto.StrategyRequest;
import com.featureflag.flags.dto.VariationCreateRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.dto.VariationUpdateRequest;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FlagAdminControllerIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private FlagRepository flagRepository;

	@Autowired
	private AuditRepository auditRepository;

	@Autowired
	private FlagCacheService flagCacheService;

	@BeforeEach
	@AfterEach
	void cleanDatabase() {
		flagRepository.deleteAll();
		auditRepository.deleteAll();
		flagCacheService.load();
	}

	@Test
	void fullLifecycleThroughCreateGetUpdateArchiveRestoreAndAudit() {
		FlagCreateRequest createRequest = new FlagCreateRequest("checkout-v2", "Checkout V2", "New checkout flow",
				FlagType.BOOLEAN, "Priya Sharma",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(),
				"tester");

		ResponseEntity<Map> createResponse = restTemplate.postForEntity("/api/v1/flags", createRequest, Map.class);
		assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Map<String, Object> created = (Map<String, Object>) createResponse.getBody().get("data");
		assertThat(created.get("key")).isEqualTo("checkout-v2");
		assertThat(created.get("lifecycle")).isEqualTo("ACTIVE");

		ResponseEntity<Map> getResponse = restTemplate.getForEntity("/api/v1/flags/checkout-v2", Map.class);
		assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

		FlagUpdateRequest updateRequest = new FlagUpdateRequest("Checkout V2", "Updated description", "Priya Sharma",
				List.of(), "tester");
		ResponseEntity<Map> updateResponse = restTemplate.exchange("/api/v1/flags/checkout-v2", HttpMethod.PATCH,
				new HttpEntity<>(updateRequest), Map.class);
		assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<String, Object> updated = (Map<String, Object>) updateResponse.getBody().get("data");
		assertThat(updated.get("description")).isEqualTo("Updated description");

		ResponseEntity<Map> archiveResponse = restTemplate.postForEntity("/api/v1/flags/checkout-v2/archive",
				new ActingRequest("tester"), Map.class);
		assertThat(((Map<String, Object>) archiveResponse.getBody().get("data")).get("lifecycle")).isEqualTo("ARCHIVED");

		ResponseEntity<Map> restoreResponse = restTemplate.postForEntity("/api/v1/flags/checkout-v2/restore",
				new ActingRequest("tester"), Map.class);
		assertThat(((Map<String, Object>) restoreResponse.getBody().get("data")).get("lifecycle")).isEqualTo("ACTIVE");

		ResponseEntity<Map> auditResponse = restTemplate.getForEntity("/api/v1/flags/checkout-v2/audit", Map.class);
		List<Map<String, Object>> auditEvents = (List<Map<String, Object>>) auditResponse.getBody().get("data");
		assertThat(auditEvents).hasSize(4);
		assertThat(auditEvents.get(0).get("action")).isEqualTo("RESTORE");
	}

	@Test
	void creatingWithTheSameKeyTwiceIsIdempotentAndReturnsTheExistingFlag() {
		FlagCreateRequest request = new FlagCreateRequest("idempotent-flag", "Idempotent", "desc", FlagType.BOOLEAN, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(),
				"tester");

		ResponseEntity<Map> first = restTemplate.postForEntity("/api/v1/flags", request, Map.class);
		ResponseEntity<Map> second = restTemplate.postForEntity("/api/v1/flags", request, Map.class);

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Map<String, Object> firstData = (Map<String, Object>) first.getBody().get("data");
		Map<String, Object> secondData = (Map<String, Object>) second.getBody().get("data");
		assertThat(secondData.get("id")).isEqualTo(firstData.get("id"));

		ResponseEntity<Map> auditResponse = restTemplate.getForEntity("/api/v1/flags/idempotent-flag/audit", Map.class);
		List<Map<String, Object>> auditEvents = (List<Map<String, Object>>) auditResponse.getBody().get("data");
		assertThat(auditEvents).hasSize(1);
	}

	@Test
	void creatingWithMissingRequiredFieldsReturnsFieldErrors() {
		FlagCreateRequest request = new FlagCreateRequest(null, null, null, null, null, List.of(), List.of(), null);

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/flags", request, Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
		Map<String, Object> details = (Map<String, Object>) error.get("details");
		Map<String, Object> fieldErrors = (Map<String, Object>) details.get("fieldErrors");
		assertThat(fieldErrors).containsKeys("key", "name", "owner", "changedBy", "type", "variations");
	}

	@Test
	void gettingUnknownFlagReturnsNotFound() {
		ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/flags/does-not-exist", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void archivingTwiceIsIdempotentAndReturns200BothTimes() {
		createFlag("archive-twice");
		ResponseEntity<Map> firstArchive = restTemplate.postForEntity("/api/v1/flags/archive-twice/archive",
				new ActingRequest("tester"), Map.class);

		ResponseEntity<Map> secondArchive = restTemplate.postForEntity("/api/v1/flags/archive-twice/archive",
				new ActingRequest("tester"), Map.class);

		assertThat(firstArchive.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(secondArchive.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(((Map<String, Object>) secondArchive.getBody().get("data")).get("lifecycle")).isEqualTo("ARCHIVED");

		ResponseEntity<Map> auditResponse = restTemplate.getForEntity("/api/v1/flags/archive-twice/audit", Map.class);
		List<Map<String, Object>> auditEvents = (List<Map<String, Object>>) auditResponse.getBody().get("data");
		assertThat(auditEvents).hasSize(2); // CREATE + one ARCHIVE, not two
	}

	@Test
	void restoringAnActiveFlagIsIdempotentAndReturns200() {
		createFlag("restore-active");

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/flags/restore-active/restore",
				new ActingRequest("tester"), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(((Map<String, Object>) response.getBody().get("data")).get("lifecycle")).isEqualTo("ACTIVE");
	}

	@Test
	void editingAnArchivedFlagIsRejectedOnBothUpdateEndpoints() {
		createFlag("archived-edit");
		restTemplate.postForEntity("/api/v1/flags/archived-edit/archive", new ActingRequest("tester"), Map.class);

		FlagUpdateRequest updateRequest = new FlagUpdateRequest("New Name", "desc", "owner", List.of(), "tester");
		ResponseEntity<Map> updateResponse = restTemplate.exchange("/api/v1/flags/archived-edit", HttpMethod.PATCH,
				new HttpEntity<>(updateRequest), Map.class);
		assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(((Map<String, Object>) updateResponse.getBody().get("error")).get("code")).isEqualTo("FLAG_ARCHIVED");

		EnvironmentConfigRequest envRequest = new EnvironmentConfigRequest(true, "off",
				List.of(rolloutStrategy(50, "on")), "tester");
		ResponseEntity<Map> envResponse = restTemplate.exchange("/api/v1/flags/archived-edit/environments/PRODUCTION",
				HttpMethod.PATCH, new HttpEntity<>(envRequest), Map.class);
		assertThat(envResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(((Map<String, Object>) envResponse.getBody().get("error")).get("code")).isEqualTo("FLAG_ARCHIVED");
	}

	@Test
	void restoringMakesAnArchivedFlagEditableAgain() {
		createFlag("restore-then-edit");
		restTemplate.postForEntity("/api/v1/flags/restore-then-edit/archive", new ActingRequest("tester"), Map.class);
		restTemplate.postForEntity("/api/v1/flags/restore-then-edit/restore", new ActingRequest("tester"), Map.class);

		FlagUpdateRequest updateRequest = new FlagUpdateRequest("Editable Again", "desc", "owner", List.of(), "tester");
		ResponseEntity<Map> updateResponse = restTemplate.exchange("/api/v1/flags/restore-then-edit", HttpMethod.PATCH,
				new HttpEntity<>(updateRequest), Map.class);

		assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(((Map<String, Object>) updateResponse.getBody().get("data")).get("name")).isEqualTo("Editable Again");
	}

	@Test
	void bulkArchiveArchivesActiveFlagsAndIgnoresAnAlreadyArchivedOne() {
		createFlag("bulk-a");
		createFlag("bulk-b");
		restTemplate.postForEntity("/api/v1/flags/bulk-b/archive", new ActingRequest("tester"), Map.class);

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/flags/bulk-archive",
				new BulkKeysRequest(List.of("bulk-a", "bulk-b", "bulk-missing"), "tester"), Map.class);

		Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
		assertThat((List<String>) data.get("archived")).containsExactly("bulk-a");
		assertThat((List<String>) data.get("alreadyArchived")).containsExactly("bulk-b");
		assertThat((List<String>) data.get("notFound")).containsExactly("bulk-missing");
	}

	@Test
	void bulkReviveRestoresArchivedFlagsAndLeavesAnAlreadyActiveOneUntouched() {
		createFlag("revive-a");
		createFlag("revive-b");
		restTemplate.postForEntity("/api/v1/flags/revive-a/archive", new ActingRequest("tester"), Map.class);

		ResponseEntity<Map> before = restTemplate.getForEntity("/api/v1/flags/revive-b", Map.class);
		Map<String, Object> productionBefore = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) before
				.getBody().get("data")).get("environments")).get("PRODUCTION");

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/flags/bulk-restore",
				new BulkKeysRequest(List.of("revive-a", "revive-b"), "tester"), Map.class);

		Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
		assertThat((List<String>) data.get("restored")).containsExactly("revive-a");
		assertThat((List<String>) data.get("alreadyActive")).containsExactly("revive-b");

		ResponseEntity<Map> after = restTemplate.getForEntity("/api/v1/flags/revive-b", Map.class);
		Map<String, Object> productionAfter = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) after
				.getBody().get("data")).get("environments")).get("PRODUCTION");
		assertThat(productionAfter).isEqualTo(productionBefore);
	}

	@Test
	void bulkArchiveValidateReportsDependencyBlockersWithoutArchivingAnything() {
		createFlag("dependency-flag");
		FlagCreateRequest dependentRequest = new FlagCreateRequest("dependent-flag", "Dependent", "desc", FlagType.BOOLEAN,
				"owner", List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")),
				List.of("dependency-flag"), "tester");
		restTemplate.postForEntity("/api/v1/flags", dependentRequest, Map.class);

		ResponseEntity<Map> validateResponse = restTemplate.postForEntity("/api/v1/flags/bulk-archive/validate",
				new BulkKeysRequest(List.of("dependency-flag"), "tester"), Map.class);
		Map<String, Object> validateData = (Map<String, Object>) validateResponse.getBody().get("data");
		assertThat(validateData.get("archivable")).isEqualTo(false);
		Map<String, Object> blocked = (Map<String, Object>) validateData.get("blocked");
		assertThat(blocked).containsKey("dependency-flag");

		ResponseEntity<Map> archiveResponse = restTemplate.postForEntity("/api/v1/flags/bulk-archive",
				new BulkKeysRequest(List.of("dependency-flag"), "tester"), Map.class);
		Map<String, Object> archiveData = (Map<String, Object>) archiveResponse.getBody().get("data");
		assertThat((List<String>) archiveData.get("archived")).isEmpty();

		ResponseEntity<Map> flagResponse = restTemplate.getForEntity("/api/v1/flags/dependency-flag", Map.class);
		assertThat(((Map<String, Object>) flagResponse.getBody().get("data")).get("lifecycle")).isEqualTo("ACTIVE");
	}

	@Test
	void singleArchiveIsAlsoBlockedByAnActiveDependent() {
		createFlag("solo-dependency");
		FlagCreateRequest dependentRequest = new FlagCreateRequest("solo-dependent", "Dependent", "desc", FlagType.BOOLEAN,
				"owner", List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")),
				List.of("solo-dependency"), "tester");
		restTemplate.postForEntity("/api/v1/flags", dependentRequest, Map.class);

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/flags/solo-dependency/archive",
				new ActingRequest("tester"), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(((Map<String, Object>) response.getBody().get("error")).get("code")).isEqualTo("FLAG_HAS_DEPENDENTS");
	}

	@Test
	void addVariationThenUpdateThenRemoveGoesThroughRealHttpAndMongo() {
		createMultivariateFlag("colors-flag");

		ResponseEntity<Map> addResponse = restTemplate.postForEntity("/api/v1/flags/colors-flag/variations",
				new VariationCreateRequest("green", "Green", "green", "tester"), Map.class);
		assertThat(addResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		List<Map<String, Object>> afterAdd = (List<Map<String, Object>>) ((Map<String, Object>) addResponse.getBody()
				.get("data")).get("variations");
		assertThat(afterAdd).extracting(v -> v.get("key")).containsExactly("red", "blue", "green");

		ResponseEntity<Map> updateResponse = restTemplate.exchange("/api/v1/flags/colors-flag/variations/green",
				HttpMethod.PATCH, new HttpEntity<>(new VariationUpdateRequest("Forest Green", "forest-green", "tester")),
				Map.class);
		assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map<String, Object>> afterUpdate = (List<Map<String, Object>>) ((Map<String, Object>) updateResponse.getBody()
				.get("data")).get("variations");
		Map<String, Object> greenVariation = afterUpdate.stream().filter(v -> "green".equals(v.get("key"))).findFirst()
				.orElseThrow();
		assertThat(greenVariation.get("name")).isEqualTo("Forest Green");
		assertThat(greenVariation.get("value")).isEqualTo("forest-green");

		ResponseEntity<Map> removeResponse = restTemplate.exchange("/api/v1/flags/colors-flag/variations/green",
				HttpMethod.DELETE, new HttpEntity<>(new ActingRequest("tester")), Map.class);
		assertThat(removeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map<String, Object>> afterRemove = (List<Map<String, Object>>) ((Map<String, Object>) removeResponse.getBody()
				.get("data")).get("variations");
		assertThat(afterRemove).extracting(v -> v.get("key")).containsExactly("red", "blue");
	}

	@Test
	void addingAVariationToABooleanFlagIsRejected() {
		createFlag("bool-flag-variation");

		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/flags/bool-flag-variation/variations",
				new VariationCreateRequest("maybe", "Maybe", "maybe", "tester"), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void removingAVariationStillReferencedByAStrategyIsRejected() {
		createMultivariateFlag("referenced-colors");
		restTemplate.postForEntity("/api/v1/flags/referenced-colors/variations",
				new VariationCreateRequest("green", "Green", "green", "tester"), Map.class);

		StrategyRequest rule = new StrategyRequest(null, StrategyType.USER_ID, List.of("u1"), null, null, 0, "green");
		restTemplate.patchForObject("/api/v1/flags/referenced-colors/environments/PRODUCTION",
				new EnvironmentConfigRequest(true, "red", List.of(rule), "tester"), Map.class);

		ResponseEntity<Map> removeResponse = restTemplate.exchange("/api/v1/flags/referenced-colors/variations/green",
				HttpMethod.DELETE, new HttpEntity<>(new ActingRequest("tester")), Map.class);

		assertThat(removeResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Map<String, Object> error = (Map<String, Object>) removeResponse.getBody().get("error");
		assertThat(error.get("code")).isEqualTo("VALIDATION_ERROR");
	}

	@Test
	void updatingAnUnknownVariationReturnsNotFound() {
		createMultivariateFlag("colors-flag-2");

		ResponseEntity<Map> response = restTemplate.exchange("/api/v1/flags/colors-flag-2/variations/does-not-exist",
				HttpMethod.PATCH, new HttpEntity<>(new VariationUpdateRequest("X", "x", "tester")), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	private StrategyRequest rolloutStrategy(int percentage, String servedVariationKey) {
		return new StrategyRequest(null, StrategyType.PERCENTAGE_ROLLOUT, List.of(), null, null, percentage, servedVariationKey);
	}

	private void createFlag(String key) {
		FlagCreateRequest request = new FlagCreateRequest(key, key, "desc", FlagType.BOOLEAN, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(),
				"tester");

		restTemplate.postForEntity("/api/v1/flags", request, Map.class);
	}

	private void createMultivariateFlag(String key) {
		FlagCreateRequest request = new FlagCreateRequest(key, key, "desc", FlagType.MULTIVARIATE, "owner",
				List.of(new VariationRequest("red", "Red", "red"), new VariationRequest("blue", "Blue", "blue")), List.of(),
				"tester");

		restTemplate.postForEntity("/api/v1/flags", request, Map.class);
	}
}
