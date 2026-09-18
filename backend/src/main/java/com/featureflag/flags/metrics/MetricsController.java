package com.featureflag.flags.metrics;

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
import com.featureflag.flags.experiment.ExperimentService;
import com.featureflag.flags.metrics.dto.ConversionEventResponse;
import com.featureflag.flags.metrics.dto.ConversionRequest;
import com.featureflag.flags.metrics.dto.ExperimentMetricsResponse;

/**
 * Impression telemetry is emitted internally from {@link com.featureflag.flags.FlagEvalController}
 * on every single-flag evaluation; this controller only exposes the two things a caller triggers
 * directly: recording a conversion, and reading side-by-side variation metrics.
 */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

	private final MetricsService metricsService;

	private final ExperimentService experimentService;

	public MetricsController(MetricsService metricsService, ExperimentService experimentService) {
		this.metricsService = metricsService;
		this.experimentService = experimentService;
	}

	/**
	 * Recording and reallocation are orchestrated here, one call each, rather than inside
	 * MetricsService calling ExperimentService directly - that would create a circular dependency
	 * between the two services, since ExperimentService also depends on MetricsService to read
	 * current metrics when computing a new allocation.
	 */
	@PostMapping("/conversions")
	public ResponseEntity<Map<String, Object>> recordConversion(@RequestBody ConversionRequest request) {
		ConversionEvent saved = metricsService.recordConversion(request);
		experimentService.reallocateIfBanditEnabled(request.flagKey(), request.environment());

		return ResponseEntity.status(201).body(Map.of("data", ConversionEventResponse.from(saved)));
	}

	@GetMapping("/{flagKey}/{environment}")
	public ResponseEntity<Map<String, Object>> metrics(@PathVariable String flagKey, @PathVariable EnvironmentKey environment,
			@RequestParam(required = false) String eventType) {
		ExperimentMetricsResponse response = metricsService.metrics(flagKey, environment, eventType);

		return ResponseEntity.ok(Map.of("data", response));
	}
}
