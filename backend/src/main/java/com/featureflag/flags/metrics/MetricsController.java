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

	private final ConversionService conversionService;

	public MetricsController(MetricsService metricsService, ConversionService conversionService) {
		this.metricsService = metricsService;
		this.conversionService = conversionService;
	}

	@PostMapping("/conversions")
	public ResponseEntity<Map<String, Object>> recordConversion(@RequestBody ConversionRequest request) {
		ConversionEvent saved = conversionService.record(request);

		return ResponseEntity.status(201).body(Map.of("data", ConversionEventResponse.from(saved)));
	}

	@GetMapping("/{flagKey}/{environment}")
	public ResponseEntity<Map<String, Object>> metrics(@PathVariable String flagKey, @PathVariable EnvironmentKey environment,
			@RequestParam(required = false) String eventType) {
		ExperimentMetricsResponse response = metricsService.metrics(flagKey, environment, eventType);

		return ResponseEntity.ok(Map.of("data", response));
	}
}
