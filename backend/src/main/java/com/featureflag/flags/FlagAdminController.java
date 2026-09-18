package com.featureflag.flags;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.dto.AuditEventResponse;
import com.featureflag.flags.dto.BulkArchiveResult;
import com.featureflag.flags.dto.BulkKeysRequest;
import com.featureflag.flags.dto.BulkRestoreResult;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.FlagResponse;
import com.featureflag.flags.dto.FlagUpdateRequest;
import com.featureflag.flags.dto.VariationCreateRequest;
import com.featureflag.flags.dto.VariationUpdateRequest;

/**
 * Write/admin surface for flag management. Kept separate from {@link FlagEvalController}, the
 * read-optimized evaluation surface, so each can scale and be secured independently.
 */
@RestController
@RequestMapping("/api/v1/flags")
public class FlagAdminController {

	private final FlagService flagService;

	private final AuditService auditService;

	public FlagAdminController(FlagService flagService, AuditService auditService) {
		this.flagService = flagService;
		this.auditService = auditService;
	}

	@GetMapping
	public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) FlagLifecycle lifecycle) {
		List<FlagResponse> flags = flagService.list(lifecycle).stream().map(FlagResponse::from).toList();

		return ResponseEntity.ok(Map.of("data", flags));
	}

	@GetMapping("/{key}")
	public ResponseEntity<Map<String, Object>> get(@PathVariable String key) {
		return ResponseEntity.ok(Map.of("data", FlagResponse.from(flagService.getOrThrow(key))));
	}

	@PostMapping
	public ResponseEntity<Map<String, Object>> create(@RequestBody FlagCreateRequest request) {
		FlagResponse response = FlagResponse.from(flagService.create(request));

		return ResponseEntity.status(201).body(Map.of("data", response));
	}

	@PatchMapping("/{key}")
	public ResponseEntity<Map<String, Object>> update(@PathVariable String key, @RequestBody FlagUpdateRequest request) {
		return ResponseEntity.ok(Map.of("data", FlagResponse.from(flagService.update(key, request))));
	}

	@PatchMapping("/{key}/environments/{environment}")
	public ResponseEntity<Map<String, Object>> updateEnvironment(@PathVariable String key,
			@PathVariable EnvironmentKey environment, @RequestBody EnvironmentConfigRequest request) {
		Flag saved = flagService.updateEnvironment(key, environment, request);

		return ResponseEntity.ok(Map.of("data", FlagResponse.from(saved)));
	}

	@PostMapping("/{key}/variations")
	public ResponseEntity<Map<String, Object>> addVariation(@PathVariable String key, @RequestBody VariationCreateRequest request) {
		Flag saved = flagService.addVariation(key, request);

		return ResponseEntity.status(201).body(Map.of("data", FlagResponse.from(saved)));
	}

	@PatchMapping("/{key}/variations/{variationKey}")
	public ResponseEntity<Map<String, Object>> updateVariation(@PathVariable String key, @PathVariable String variationKey,
			@RequestBody VariationUpdateRequest request) {
		Flag saved = flagService.updateVariation(key, variationKey, request);

		return ResponseEntity.ok(Map.of("data", FlagResponse.from(saved)));
	}

	@DeleteMapping("/{key}/variations/{variationKey}")
	public ResponseEntity<Map<String, Object>> removeVariation(@PathVariable String key, @PathVariable String variationKey,
			@RequestBody ActingRequest request) {
		Flag saved = flagService.removeVariation(key, variationKey, request.changedBy());

		return ResponseEntity.ok(Map.of("data", FlagResponse.from(saved)));
	}

	@PostMapping("/{key}/archive")
	public ResponseEntity<Map<String, Object>> archive(@PathVariable String key, @RequestBody ActingRequest request) {
		return ResponseEntity.ok(Map.of("data", FlagResponse.from(flagService.archive(key, request.changedBy()))));
	}

	@PostMapping("/{key}/restore")
	public ResponseEntity<Map<String, Object>> restore(@PathVariable String key, @RequestBody ActingRequest request) {
		return ResponseEntity.ok(Map.of("data", FlagResponse.from(flagService.restore(key, request.changedBy()))));
	}

	@GetMapping("/{key}/audit")
	public ResponseEntity<Map<String, Object>> audit(@PathVariable String key) {
		flagService.getOrThrow(key);
		List<AuditEventResponse> history = auditService.history(key).stream().map(AuditEventResponse::from).toList();

		return ResponseEntity.ok(Map.of("data", history));
	}

	@PostMapping("/bulk-archive/validate")
	public ResponseEntity<Map<String, Object>> validateBulkArchive(@RequestBody BulkKeysRequest request) {
		Map<String, List<String>> blocked = flagService.validateBulkArchive(request.keys());

		return ResponseEntity.ok(Map.of("data", Map.of("blocked", blocked, "archivable", blocked.isEmpty())));
	}

	@PostMapping("/bulk-archive")
	public ResponseEntity<Map<String, Object>> bulkArchive(@RequestBody BulkKeysRequest request) {
		BulkArchiveResult result = flagService.bulkArchive(request.keys(), request.changedBy());

		return ResponseEntity.ok(Map.of("data", result));
	}

	@PostMapping("/bulk-restore")
	public ResponseEntity<Map<String, Object>> bulkRestore(@RequestBody BulkKeysRequest request) {
		BulkRestoreResult result = flagService.bulkRestore(request.keys(), request.changedBy());

		return ResponseEntity.ok(Map.of("data", result));
	}
}
