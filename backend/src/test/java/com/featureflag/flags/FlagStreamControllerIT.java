package com.featureflag.flags;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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

	@BeforeEach
	@AfterEach
	void cleanDatabase() {
		flagRepository.deleteAll();
		auditRepository.deleteAll();
		flagCacheService.load();
	}

	@Test
	void subscriberReceivesAFlagChangedEventWithinFiveSecondsOfAWrite() throws Exception {
		FlagCreateRequest request = new FlagCreateRequest("stream-flag", "Stream Flag", "desc", FlagType.BOOLEAN, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(),
				"tester");
		restTemplate.postForEntity("/api/v1/flags", request, Map.class);

		HttpClient client = HttpClient.newHttpClient();
		HttpRequest streamRequest = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/api/v1/stream/PRODUCTION"))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		List<String> receivedLines = new CopyOnWriteArrayList<>();
		CompletableFuture<HttpResponse<Void>> streamFuture = client
				.sendAsync(streamRequest, responseInfo -> HttpResponse.BodySubscribers
						.fromLineSubscriber(new java.util.concurrent.Flow.Subscriber<>() {
							private java.util.concurrent.Flow.Subscription subscription;

							@Override
							public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
								this.subscription = subscription;
								subscription.request(Long.MAX_VALUE);
							}

							@Override
							public void onNext(String item) {
								receivedLines.add(item);
							}

							@Override
							public void onError(Throwable throwable) {
							}

							@Override
							public void onComplete() {
							}
						}));

		// Give the SSE subscription time to register with FlagStreamService before writing.
		waitUntilSubscribed();

		StrategyRequest rollout = new StrategyRequest(null, StrategyType.PERCENTAGE_ROLLOUT, List.of(), null, null, 100, "on");
		restTemplate.patchForObject("/api/v1/flags/stream-flag/environments/PRODUCTION",
				new EnvironmentConfigRequest(true, "off", List.of(rollout), "tester"), Map.class);

		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
		boolean received = false;

		while (System.currentTimeMillis() < deadline) {
			if (receivedLines.stream().anyMatch(line -> line.contains("flag-changed"))) {
				received = true;
				break;
			}

			Thread.sleep(100);
		}

		streamFuture.cancel(true);

		assertThat(received).isTrue();
	}

	private void waitUntilSubscribed() throws InterruptedException {
		Thread.sleep(300);
	}
}
