package com.featureflag.shared;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

	private static final int DEGRADED_STATUS = 503;

	private final MongoTemplate mongoTemplate;

	public HealthController(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	@GetMapping("/api/v1/health")
	public ResponseEntity<Map<String, Object>> health() {
		boolean connected = isConnected();
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("status", connected ? "ok" : "degraded");
		status.put("database", connected ? "connected" : "disconnected");

		return ResponseEntity.status(connected ? 200 : DEGRADED_STATUS).body(Map.of("data", status));
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
