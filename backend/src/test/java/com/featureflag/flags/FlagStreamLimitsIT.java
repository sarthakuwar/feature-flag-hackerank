package com.featureflag.flags;

import static com.featureflag.flags.rollout.RolloutFixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import com.featureflag.shared.MutableClock;

/** Stream limits and housekeeping: a low cap and a fast heartbeat so they are observable without waiting. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "featureflag.stream.max-subscribers-per-environment=2", "featureflag.stream.heartbeat-interval-ms=100" })
@ActiveProfiles("test")
class FlagStreamLimitsIT {

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		@Primary
		MutableClock testClock() {
			return new MutableClock(T0);
		}
	}

	@LocalServerPort
	private int port;

	@Autowired
	private FlagStreamService flagStreamService;

	@BeforeEach
	void startWithNoSubscribers() {
		flagStreamService.closeAllSubscribers();
	}

	@Test
	void anIdleStreamReceivesHeartbeatComments() {
		try (SseTestClient stream = SseTestClient.open(port, flagStreamService, EnvironmentKey.PRODUCTION)) {
			stream.awaitLine(":heartbeat");
		}
	}

	@Test
	void aThirdSubscriberIsRefusedWith503AndTheErrorEnvelope() throws Exception {
		try (SseTestClient first = SseTestClient.open(port, flagStreamService, EnvironmentKey.DEVELOPMENT);
				SseTestClient second = SseTestClient.open(port, flagStreamService, EnvironmentKey.DEVELOPMENT)) {
			HttpResponse<String> refused = get(EnvironmentKey.DEVELOPMENT);

			assertThat(refused.statusCode()).isEqualTo(503);
			assertThat(refused.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
			assertThat(refused.body()).contains("\"code\":\"STREAM_CAPACITY\"");
			assertThat(flagStreamService.subscriberCount(EnvironmentKey.DEVELOPMENT)).isEqualTo(2);

			try (SseTestClient otherEnvironment = SseTestClient.open(port, flagStreamService, EnvironmentKey.STAGING)) {
				assertThat(flagStreamService.subscriberCount(EnvironmentKey.STAGING)).isEqualTo(1);
			}
		}
	}

	@Test
	void aClientThatLeavesFreesItsSlotForANewSubscriber() {
		SseTestClient first = SseTestClient.open(port, flagStreamService, EnvironmentKey.DEVELOPMENT);
		SseTestClient second = SseTestClient.open(port, flagStreamService, EnvironmentKey.DEVELOPMENT);

		first.close();
		SseTestClient.awaitSubscribers(flagStreamService, EnvironmentKey.DEVELOPMENT, 1);

		try (SseTestClient replacement = SseTestClient.open(port, flagStreamService, EnvironmentKey.DEVELOPMENT)) {
			assertThat(flagStreamService.subscriberCount(EnvironmentKey.DEVELOPMENT)).isEqualTo(2);
		}

		second.close();
	}

	@Test
	void eventTimestampsComeFromTheInjectedClock() {
		try (SseTestClient stream = SseTestClient.open(port, flagStreamService, EnvironmentKey.PRODUCTION)) {
			flagStreamService.publishFlagChanged(EnvironmentKey.PRODUCTION, "some-flag", "UPDATE");

			stream.awaitLine("\"changedAt\":\"" + T0 + "\"");
		}
	}

	@Test
	void closingAllSubscribersEndsTheirResponsesCleanly() {
		SseTestClient stream = SseTestClient.open(port, flagStreamService, EnvironmentKey.PRODUCTION);

		flagStreamService.closeAllSubscribers();

		stream.awaitEnd();
		assertThat(flagStreamService.subscriberCount(EnvironmentKey.PRODUCTION)).isZero();
	}

	private HttpResponse<String> get(EnvironmentKey environment) throws Exception {
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/v1/stream/" + environment.name()))
				.header("Accept", "text/event-stream").GET().build();

		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}
}
