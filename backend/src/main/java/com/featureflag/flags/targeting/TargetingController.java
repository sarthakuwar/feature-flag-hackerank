package com.featureflag.flags.targeting;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.dto.ActingRequest;
import com.featureflag.flags.targeting.dto.BulkTargetRequest;
import com.featureflag.flags.targeting.dto.IndividualTargetsRequest;
import com.featureflag.flags.targeting.dto.RuleEnabledRequest;
import com.featureflag.flags.targeting.dto.RuleMoveRequest;
import com.featureflag.flags.targeting.dto.RuleOrderRequest;
import com.featureflag.flags.targeting.dto.TargetingRuleRequest;

/**
 * Targeting rules and individual targets for one flag in one environment. Rule-level writes return
 * the rule they touched; collection-level writes (delete, reorder, move, individual targets) return
 * the environment's whole targeting state so a UI can re-render from a single response.
 */
@RestController
@RequestMapping("/api/v1/flags/{key}/environments/{environment}")
public class TargetingController {

	private final TargetingService targetingService;

	public TargetingController(TargetingService targetingService) {
		this.targetingService = targetingService;
	}

	@GetMapping("/targeting")
	public ResponseEntity<Map<String, Object>> get(@PathVariable String key, @PathVariable EnvironmentKey environment) {
		return ResponseEntity.ok(Map.of("data", targetingService.get(key, environment)));
	}

	@PostMapping("/rules")
	public ResponseEntity<Map<String, Object>> createRule(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@RequestBody TargetingRuleRequest request) {
		return ResponseEntity.status(201).body(Map.of("data", targetingService.createRule(key, environment, request)));
	}

	/** Declared before /rules/{ruleId} so the literal "order" segment is never read as a rule id. */
	@PutMapping("/rules/order")
	public ResponseEntity<Map<String, Object>> reorderRules(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@RequestBody RuleOrderRequest request) {
		return ResponseEntity.ok(Map.of("data", targetingService.reorderRules(key, environment, request)));
	}

	@PutMapping("/rules/{ruleId}")
	public ResponseEntity<Map<String, Object>> updateRule(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@PathVariable String ruleId, @RequestBody TargetingRuleRequest request) {
		return ResponseEntity.ok(Map.of("data", targetingService.updateRule(key, environment, ruleId, request)));
	}

	@PatchMapping("/rules/{ruleId}")
	public ResponseEntity<Map<String, Object>> setRuleEnabled(@PathVariable String key,
			@PathVariable EnvironmentKey environment, @PathVariable String ruleId, @RequestBody RuleEnabledRequest request) {
		return ResponseEntity.ok(Map.of("data", targetingService.setRuleEnabled(key, environment, ruleId, request)));
	}

	@PostMapping("/rules/{ruleId}/move")
	public ResponseEntity<Map<String, Object>> moveRule(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@PathVariable String ruleId, @RequestBody RuleMoveRequest request) {
		return ResponseEntity.ok(Map.of("data", targetingService.moveRule(key, environment, ruleId, request)));
	}

	@DeleteMapping("/rules/{ruleId}")
	public ResponseEntity<Map<String, Object>> deleteRule(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@PathVariable String ruleId, @RequestBody ActingRequest request) {
		return ResponseEntity
				.ok(Map.of("data", targetingService.deleteRule(key, environment, ruleId, request.changedBy())));
	}

	@PutMapping("/individual-targets")
	public ResponseEntity<Map<String, Object>> replaceIndividualTargets(@PathVariable String key,
			@PathVariable EnvironmentKey environment, @RequestBody IndividualTargetsRequest request) {
		return ResponseEntity.ok(Map.of("data", targetingService.replaceIndividualTargets(key, environment, request)));
	}

	@PostMapping("/individual-targets/bulk")
	public ResponseEntity<Map<String, Object>> bulkTarget(@PathVariable String key, @PathVariable EnvironmentKey environment,
			@RequestBody BulkTargetRequest request) {
		return ResponseEntity.ok(Map.of("data", targetingService.bulkTarget(key, environment, request)));
	}
}
