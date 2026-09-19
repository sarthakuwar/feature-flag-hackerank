package com.featureflag.flags.targeting;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.targeting.dto.MobileApplicationResponse;
import com.featureflag.flags.targeting.dto.SegmentResponse;

/**
 * The SDK-side "persistent store" feed for local evaluation: an SDK bootstraps the flags, these
 * segment definitions and the application registry once, keeps them fresh from the {@code
 * segment-changed} / {@code application-changed} events on the SSE stream, and evaluates rules that
 * reference segments entirely in-process. Reads the in-memory {@link TargetingCacheService}, never MongoDB.
 */
@RestController
@RequestMapping("/api/v1/eval")
public class TargetingSyncController {

	private final TargetingCacheService targetingCacheService;

	public TargetingSyncController(TargetingCacheService targetingCacheService) {
		this.targetingCacheService = targetingCacheService;
	}

	@GetMapping("/{environment}/segments")
	public ResponseEntity<Map<String, Object>> segments(@PathVariable EnvironmentKey environment) {
		List<SegmentResponse> segments = targetingCacheService.segmentsIn(environment).stream().map(SegmentResponse::from)
				.toList();

		return ResponseEntity.ok(Map.of("data", segments));
	}

	@GetMapping("/applications")
	public ResponseEntity<Map<String, Object>> applications() {
		List<MobileApplicationResponse> applications = targetingCacheService.applications().stream()
				.map(MobileApplicationResponse::from).toList();

		return ResponseEntity.ok(Map.of("data", applications));
	}
}
