package com.featureflag.flags;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.StrategyRequest;
import com.featureflag.flags.dto.VariationRequest;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FlagStreamControllerIT {

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private FlagRepository flagRepository;

	@Autowired
	private AuditRepository auditRepository;

	@Autowired
	private FlagCacheService flagCacheService;

	@Autowired
	private FlagStreamService flagStreamService;

	@BeforeEach
	@AfterEach
	void cleanDatabase() {
		flagRepository.deleteAll();
		auditRepository.deleteAll();
		flagCacheService.load();
	}

	@Test
	void subscriberReceivesAFlagChangedEventWithinFiveSecondsOfAWrite() {
		FlagCreateRequest request = new FlagCreateRequest("stream-flag", "Stream Flag", "desc", FlagType.BOOLEAN, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(),
				"tester");
		restTemplate.postForEntity("/api/v1/flags", request, Map.class);

		try (SseTestClient stream = SseTestClient.open(port, flagStreamService, EnvironmentKey.PRODUCTION)) {
			StrategyRequest rollout = new StrategyRequest(null, StrategyType.PERCENTAGE_ROLLOUT, List.of(), null, null, 100, "on");
			restTemplate.patchForObject("/api/v1/flags/stream-flag/environments/PRODUCTION",
					new EnvironmentConfigRequest(true, "off", List.of(rollout), "tester"), Map.class);

			stream.awaitLine("flag-changed");
		}
	}
}
