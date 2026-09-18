package com.featureflag.flags;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.featureflag.flags.dto.BootstrapFlagResponse;
import com.featureflag.flags.dto.EvaluationRequest;
import com.featureflag.flags.metrics.MetricsService;
import com.featureflag.shared.ApiException;

/**
 * Read-optimized evaluation surface a real SDK would call. Evaluates against the in-memory
 * {@link FlagCacheService} only, never MongoDB directly, so it stays fast regardless of write
 * volume. Kept separate from {@link FlagAdminController} per the read/write API-separation
 * guidance in the spec.
 *
 * <p>The two evaluation routes are POST, not GET, even though neither writes anything. The user
 * context they take can carry PII (email, country, plan); a GET would put that in the URL, which
 * lands in access logs, browser history, and any proxy in between. The bootstrap route stays GET
 * since it only takes an environment - no per-user data ever appears in that URL.
 */
@RestController
@RequestMapping("/api/v1/eval")
public class FlagEvalController {

	private final FlagCacheService flagCacheService;

	private final FlagEvaluationService flagEvaluationService;

	private final MetricsService metricsService;

	public FlagEvalController(FlagCacheService flagCacheService, FlagEvaluationService flagEvaluationService,
			MetricsService metricsService) {
		this.flagCacheService = flagCacheService;
		this.flagEvaluationService = flagEvaluationService;
		this.metricsService = metricsService;
	}

	/**
	 * The one route that stands in for a real SDK's isEnabled/getVariant call, so it's the only one
	 * that emits an impression - fired async (see MetricsService.recordImpression) after the result
	 * is already computed, so telemetry never adds latency or a failure mode to evaluation itself.
	 * Bulk-evaluate and bootstrap don't emit; neither is "one SDK evaluating one flag for one user."
	 */
	@PostMapping("/{environment}/{key}")
	public ResponseEntity<Map<String, Object>> evaluateOne(@PathVariable EnvironmentKey environment, @PathVariable String key,
			@RequestBody(required = false) EvaluationRequest request) {
		Flag flag = flagCacheService.get(key)
				.filter(candidate -> candidate.getLifecycle() == FlagLifecycle.ACTIVE)
				.orElseThrow(() -> new ApiException(404, "FLAG_NOT_FOUND", "No active flag exists with key '" + key + "'."));

		UserContext userContext = userContextFrom(request);
		EvaluationResult result = flagEvaluationService.evaluate(flag, environment, userContext);
		int bucket = flagEvaluationService.bucketFor(key, userContext);
		metricsService.recordImpression(key, environment, result.variationKey(), userContext.userId(), bucket, result.reason());

		return ResponseEntity.ok(Map.of("data", result));
	}

	@PostMapping("/{environment}")
	public ResponseEntity<Map<String, Object>> evaluateAll(@PathVariable EnvironmentKey environment,
			@RequestBody(required = false) EvaluationRequest request) {
		UserContext userContext = userContextFrom(request);
		List<EvaluationResult> results = flagCacheService.all().stream()
				.filter(flag -> flag.getLifecycle() == FlagLifecycle.ACTIVE)
				.map(flag -> flagEvaluationService.evaluate(flag, environment, userContext))
				.toList();

		return ResponseEntity.ok(Map.of("data", results));
	}

	@GetMapping("/{environment}/bootstrap")
	public ResponseEntity<Map<String, Object>> bootstrap(@PathVariable EnvironmentKey environment) {
		List<BootstrapFlagResponse> flags = flagCacheService.all().stream()
				.filter(flag -> flag.getLifecycle() == FlagLifecycle.ACTIVE)
				.map(flag -> BootstrapFlagResponse.from(flag, flag.getEnvironments().get(environment)))
				.toList();

		return ResponseEntity.ok(Map.of("data", flags));
	}

	private UserContext userContextFrom(EvaluationRequest request) {
		return request == null ? UserContext.anonymous(null) : new UserContext(request.userId(), request.attributes());
	}
}
