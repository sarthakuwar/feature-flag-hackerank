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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.targeting.dto.SegmentCreateRequest;
import com.featureflag.flags.targeting.dto.SegmentMembersRequest;
import com.featureflag.flags.targeting.dto.SegmentResponse;
import com.featureflag.flags.targeting.dto.SegmentSummaryResponse;
import com.featureflag.flags.targeting.dto.SegmentUpdateRequest;

/** Admin surface for segments. Segments are per environment, so the environment leads every route. */
@RestController
@RequestMapping("/api/v1/segments/{environment}")
public class SegmentController {

	private final SegmentService segmentService;

	public SegmentController(SegmentService segmentService) {
		this.segmentService = segmentService;
	}

	@GetMapping
	public ResponseEntity<Map<String, Object>> list(@PathVariable EnvironmentKey environment,
			@RequestParam(required = false) String q, @RequestParam(required = false) String contextKind) {
		List<SegmentSummaryResponse> segments = segmentService.list(environment, q, contextKind).stream()
				.map(SegmentSummaryResponse::from).toList();

		return ResponseEntity.ok(Map.of("data", segments));
	}

	@PostMapping
	public ResponseEntity<Map<String, Object>> create(@PathVariable EnvironmentKey environment,
			@RequestBody SegmentCreateRequest request) {
		return ResponseEntity.status(201).body(Map.of("data", SegmentResponse.from(segmentService.create(environment, request))));
	}

	@GetMapping("/{key}")
	public ResponseEntity<Map<String, Object>> get(@PathVariable EnvironmentKey environment, @PathVariable String key) {
		return ResponseEntity.ok(Map.of("data", SegmentResponse.from(segmentService.getOrThrow(environment, key))));
	}

	@PatchMapping("/{key}")
	public ResponseEntity<Map<String, Object>> update(@PathVariable EnvironmentKey environment, @PathVariable String key,
			@RequestBody SegmentUpdateRequest request) {
		return ResponseEntity.ok(Map.of("data", SegmentResponse.from(segmentService.update(environment, key, request))));
	}

	@PostMapping("/{key}/members")
	public ResponseEntity<Map<String, Object>> changeMembers(@PathVariable EnvironmentKey environment,
			@PathVariable String key, @RequestBody SegmentMembersRequest request) {
		return ResponseEntity.ok(Map.of("data", segmentService.changeMembers(environment, key, request)));
	}

	@GetMapping("/{key}/usage")
	public ResponseEntity<Map<String, Object>> usage(@PathVariable EnvironmentKey environment, @PathVariable String key) {
		return ResponseEntity.ok(Map.of("data", segmentService.usage(environment, key)));
	}

	@DeleteMapping("/{key}")
	public ResponseEntity<Map<String, Object>> delete(@PathVariable EnvironmentKey environment, @PathVariable String key,
			@RequestBody ActingRequest request) {
		segmentService.delete(environment, key, request.changedBy());

		return ResponseEntity.ok(Map.of("data", Map.of("deleted", key)));
	}
}
