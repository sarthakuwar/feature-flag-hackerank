package com.featureflag.flags.rollout;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.ProgressiveRollout;
import com.featureflag.flags.RolloutStatus;
import com.featureflag.flags.rollout.dto.ProgressiveRolloutResponse;
import com.featureflag.flags.rollout.dto.StartProgressiveRolloutRequest;
import com.featureflag.flags.rollout.dto.StopProgressiveRolloutRequest;

/**
 * The progressive rollout control plane: start a rollout on a targeting rule, watch it, and stop it.
 * Kept separate from {@link com.featureflag.flags.FlagAdminController} for the same reason the
 * experiment surface is - a cohesive sub-concern with its own DTOs. Every response carries the
 * rollout's live progress (current percentage and stage, next stage time), so a UI never needs to
 * re-derive the schedule.
 */
@RestController
@RequestMapping("/api/v1/flags/{key}/environments/{environment}/progressive-rollouts")
public class ProgressiveRolloutController {

	private final ProgressiveRolloutService rolloutService;

	private final Clock clock;

	public ProgressiveRolloutController(ProgressiveRolloutService rolloutService, Clock clock) {
		this.rolloutService = rolloutService;
		this.clock = clock;
	}

	@GetMapping
	public ResponseEntity<Map<String, Object>> list(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@RequestParam(required = false) RolloutStatus status) {
		List<ProgressiveRolloutResponse> rollouts = rolloutService.list(key, environment, status).stream()
				.map(this::toResponse).toList();

		return ResponseEntity.ok(Map.of("data", rollouts));
	}

	@GetMapping("/{rolloutId}")
	public ResponseEntity<Map<String, Object>> get(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@PathVariable String rolloutId) {
		return ResponseEntity.ok(Map.of("data", toResponse(rolloutService.get(key, environment, rolloutId))));
	}

	@PostMapping
	public ResponseEntity<Map<String, Object>> start(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@RequestBody StartProgressiveRolloutRequest request) {
		ProgressiveRollout rollout = rolloutService.start(key, environment, request);

		return ResponseEntity.status(201).body(Map.of("data", toResponse(rollout)));
	}

	@PostMapping("/{rolloutId}/stop")
	public ResponseEntity<Map<String, Object>> stop(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@PathVariable String rolloutId, @RequestBody StopProgressiveRolloutRequest request) {
		ProgressiveRollout rollout = rolloutService.stop(key, environment, rolloutId, request.variationKey(),
				request.changedBy());

		return ResponseEntity.ok(Map.of("data", toResponse(rollout)));
	}

	private ProgressiveRolloutResponse toResponse(ProgressiveRollout rollout) {
		return ProgressiveRolloutResponse.from(rollout, clock.instant());
	}
}
