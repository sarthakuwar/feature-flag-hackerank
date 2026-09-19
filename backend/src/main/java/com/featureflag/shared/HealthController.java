package com.featureflag.shared;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.featureflag.flags.FlagCacheService;
import com.featureflag.flags.targeting.TargetingCacheService;

/**
 * {@code /health} reports MongoDB state (503 when it is unreachable). {@code /health/live} only
 * says the process answers and does no I/O, for a liveness probe that must never restart a healthy
 * process because its database is down. {@code /health/ready} adds the in-memory caches flag
 * evaluation reads, for a probe that decides whether to send traffic.
 */
@RestController
public class HealthController {

	private static final int DEGRADED_STATUS = 503;

	private final MongoTemplate mongoTemplate;

	private final FlagCacheService flagCacheService;

	private final TargetingCacheService targetingCacheService;

	public HealthController(MongoTemplate mongoTemplate, FlagCacheService flagCacheService,
			TargetingCacheService targetingCacheService) {
		this.mongoTemplate = mongoTemplate;
		this.flagCacheService = flagCacheService;
		this.targetingCacheService = targetingCacheService;
	}

	@GetMapping("/api/v1/health")
	public ResponseEntity<Map<String, Object>> health() {
		boolean connected = isConnected();
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("status", connected ? "ok" : "degraded");
		status.put("database", connected ? "connected" : "disconnected");

		return ResponseEntity.status(connected ? 200 : DEGRADED_STATUS).body(Map.of("data", status));
	}

	@GetMapping("/api/v1/health/live")
	public ResponseEntity<Map<String, Object>> live() {
		return ResponseEntity.ok(Map.of("data", Map.of("status", "ok")));
	}

	@GetMapping("/api/v1/health/ready")
	public ResponseEntity<Map<String, Object>> ready() {
		boolean connected = isConnected();
		boolean cachesLoaded = flagCacheService.isLoaded() && targetingCacheService.isLoaded();
		boolean ready = connected && cachesLoaded;
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("status", ready ? "ready" : "not_ready");
		status.put("database", connected ? "connected" : "disconnected");
		status.put("caches", cachesLoaded ? "loaded" : "loading");

		return ResponseEntity.status(ready ? 200 : DEGRADED_STATUS).body(Map.of("data", status));
	}

	private boolean isConnected() {
		try {
			mongoTemplate.executeCommand(new Document("ping", 1));

			return true;
		} catch (RuntimeException exception) {
			return false;
		}
	}
}
