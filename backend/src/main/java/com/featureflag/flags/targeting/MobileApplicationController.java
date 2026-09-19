package com.featureflag.flags.targeting;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.targeting.dto.MobileApplicationRequest;
import com.featureflag.flags.targeting.dto.MobileApplicationResponse;
import com.featureflag.flags.targeting.dto.MobileApplicationUpdateRequest;

/** Admin surface for the mobile application registry that backs "app version supported / unsupported" targeting. */
@RestController
@RequestMapping("/api/v1/mobile-applications")
public class MobileApplicationController {

	private final MobileApplicationService applicationService;

	public MobileApplicationController(MobileApplicationService applicationService) {
		this.applicationService = applicationService;
	}

	@GetMapping
	public ResponseEntity<Map<String, Object>> list() {
		List<MobileApplicationResponse> applications = applicationService.list().stream()
				.map(MobileApplicationResponse::from).toList();

		return ResponseEntity.ok(Map.of("data", applications));
	}

	@PostMapping
	public ResponseEntity<Map<String, Object>> create(@RequestBody MobileApplicationRequest request) {
		return ResponseEntity.status(201)
				.body(Map.of("data", MobileApplicationResponse.from(applicationService.create(request))));
	}

	@GetMapping("/{key}")
	public ResponseEntity<Map<String, Object>> get(@PathVariable String key) {
		return ResponseEntity.ok(Map.of("data", MobileApplicationResponse.from(applicationService.getOrThrow(key))));
	}

	@PatchMapping("/{key}")
	public ResponseEntity<Map<String, Object>> update(@PathVariable String key,
			@RequestBody MobileApplicationUpdateRequest request) {
		return ResponseEntity.ok(Map.of("data", MobileApplicationResponse.from(applicationService.update(key, request))));
	}

	@DeleteMapping("/{key}")
	public ResponseEntity<Map<String, Object>> delete(@PathVariable String key, @RequestBody ActingRequest request) {
		applicationService.delete(key, request.changedBy());

		return ResponseEntity.ok(Map.of("data", Map.of("deleted", key)));
	}
}
