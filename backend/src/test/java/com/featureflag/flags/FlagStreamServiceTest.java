package com.featureflag.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;

import com.featureflag.shared.ApiException;
import com.featureflag.shared.MutableClock;

class FlagStreamServiceTest {

	private static final EnvironmentKey PRODUCTION = EnvironmentKey.PRODUCTION;

	private static final long THIRTY_MINUTES = 30 * 60 * 1000L;

	private final MutableClock clock = new MutableClock(Instant.parse("2026-03-01T12:00:00Z"));

	private FlagStreamService service = new FlagStreamService(clock, THIRTY_MINUTES, 2);

	@AfterEach
	void stopService() {
		service.shutdown();
	}

	@Test
	void emittersUseTheConfiguredFiniteTimeout() {
		assertThat(service.subscribe(PRODUCTION).getTimeout()).isEqualTo(THIRTY_MINUTES);

		service.shutdown();
		service = new FlagStreamService(clock, 1_234L, 2);

		assertThat(service.subscribe(PRODUCTION).getTimeout()).isEqualTo(1_234L);
	}

	@Test
	void subscribersAreCountedPerEnvironment() {
		service.subscribe(PRODUCTION);
		service.subscribe(PRODUCTION);
		service.subscribe(EnvironmentKey.STAGING);

		assertThat(service.subscriberCount(PRODUCTION)).isEqualTo(2);
		assertThat(service.subscriberCount(EnvironmentKey.STAGING)).isEqualTo(1);
		assertThat(service.subscriberCount(EnvironmentKey.DEVELOPMENT)).isZero();
	}

	@Test
	void anEnvironmentAtItsCapRefusesWith503StreamCapacityWhileOthersStillAccept() {
		service.subscribe(PRODUCTION);
		service.subscribe(PRODUCTION);

		assertThatThrownBy(() -> service.subscribe(PRODUCTION)).isInstanceOfSatisfying(ApiException.class, exception -> {
			assertThat(exception.getStatusCode()).isEqualTo(503);
			assertThat(exception.getCode()).isEqualTo("STREAM_CAPACITY");
		});
		assertThat(service.subscriberCount(PRODUCTION)).as("a refused subscriber is not counted").isEqualTo(2);
		assertThat(service.subscribe(EnvironmentKey.STAGING)).isNotNull();
	}

	@Test
	void shutdownCompletesEveryEmitterAndEmptiesTheRegistry() {
		service.subscribe(PRODUCTION);
		service.subscribe(EnvironmentKey.STAGING);

		service.shutdown();

		assertThat(service.subscriberCount(PRODUCTION)).isZero();
		assertThat(service.subscriberCount(EnvironmentKey.STAGING)).isZero();
	}

	@Test
	void publishingWithNoSubscribersDoesNothing() {
		service.publishFlagChanged(PRODUCTION, "flag", "UPDATE");
		service.publishSegmentChanged(PRODUCTION, "segment", "CREATE");
		service.publishApplicationChanged("app", "CREATE");
		service.sendHeartbeats();

		assertThat(service.subscriberCount(PRODUCTION)).isZero();
	}

	@Test
	void theServiceStopsInTheSamePhaseAsTheWebServersGracefulShutdown() {
		assertThat(service.getPhase()).isEqualTo(SmartLifecycle.DEFAULT_PHASE);

		service.start();
		assertThat(service.isRunning()).isTrue();

		service.subscribe(PRODUCTION);
		service.stop();

		assertThat(service.isRunning()).isFalse();
		assertThat(service.subscriberCount(PRODUCTION)).isZero();
	}
}
