package com.featureflag.flags.experiment;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.Flag;
import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.dto.FlagResponse;
import com.featureflag.flags.experiment.dto.BanditConfigRequest;
import com.featureflag.flags.experiment.dto.PromoteRequest;
import com.featureflag.flags.experiment.dto.SetWeightsRequest;

/**
 * The allocation control plane for an experiment: kept separate from {@link
 * com.featureflag.flags.FlagAdminController} for the same reason the eval and stream surfaces
 * already are - a cohesive sub-concern with its own DTOs, manipulating the same {@link Flag}
 * aggregate through {@link ExperimentService}.
 */
@RestController
@RequestMapping("/api/v1/flags/{key}/environments/{environment}/experiment")
public class ExperimentController {

	private final ExperimentService experimentService;

	public ExperimentController(ExperimentService experimentService) {
		this.experimentService = experimentService;
	}

	@PutMapping("/weights")
	public ResponseEntity<Map<String, Object>> setWeights(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@RequestBody SetWeightsRequest request) {
		Flag saved = experimentService.setVariationWeights(key, environment, request.weights(), request.changedBy());

		return ResponseEntity.ok(Map.of("data", FlagResponse.from(saved)));
	}

	@PutMapping("/bandit")
	public ResponseEntity<Map<String, Object>> configureBandit(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@RequestBody BanditConfigRequest request) {
		Flag saved = experimentService.configureBandit(key, environment, request.algorithm(), request.epsilon(),
				request.changedBy());

		return ResponseEntity.ok(Map.of("data", FlagResponse.from(saved)));
	}

	@PostMapping("/reallocate")
	public ResponseEntity<Map<String, Object>> reallocate(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@RequestBody ActingRequest request) {
		Flag saved = experimentService.reallocateNow(key, environment, request.changedBy());

		return ResponseEntity.ok(Map.of("data", FlagResponse.from(saved)));
	}

	@PostMapping("/promote")
	public ResponseEntity<Map<String, Object>> promote(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@RequestBody PromoteRequest request) {
		Flag saved = experimentService.promoteToFullRollout(key, environment, request.winningVariationKey(), request.changedBy());

		return ResponseEntity.ok(Map.of("data", FlagResponse.from(saved)));
	}
}
