package com.featureflag.flags;

import java.io.IOException;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.featureflag.shared.ApiException;

import jakarta.annotation.PreDestroy;

/**
 * In-app stand-in for the streaming endpoint a real SDK would hold a persistent connection to.
 * WebSockets are not available in this runtime (GUIDELINES.md), so this uses plain HTTP
 * Server-Sent Events, which spring-boot-starter-web already ships (no new dependency). Every
 * flag write broadcasts to every subscriber of the affected environment; a single process is
 * plenty to demonstrate sub-5-second propagation without a real pub/sub broker.
 *
 * <p>Publishing never writes to a socket on the caller's thread: each subscriber owns a small
 * bounded queue that a pool thread drains, so a stalled client can delay only itself. A subscriber
 * whose queue overflows is dropped and its browser reconnects. Connections end after a finite
 * timeout for the same reason, and a heartbeat comment keeps idle ones open through proxies.
 */
@Service
public class FlagStreamService implements SmartLifecycle {

	private static final Logger LOG = LoggerFactory.getLogger(FlagStreamService.class);

	private static final int PENDING_EVENTS_PER_SUBSCRIBER = 256;

	private static final int DISPATCH_THREADS = 8;

	private static final int SERVICE_UNAVAILABLE = 503;

	private record StreamEvent(String name, Map<String, Object> payload) {

		static StreamEvent heartbeat() {
			return new StreamEvent(null, null);
		}

		SseEmitter.SseEventBuilder toBuilder() {
			if (name == null) {
				return SseEmitter.event().comment("heartbeat");
			}

			return SseEmitter.event().name(name).data(payload);
		}
	}

	private static final class Subscriber {

		private final EnvironmentKey environment;

		private final SseEmitter emitter;

		private final ArrayBlockingQueue<StreamEvent> pending = new ArrayBlockingQueue<>(PENDING_EVENTS_PER_SUBSCRIBER);

		private final AtomicBoolean draining = new AtomicBoolean();

		private volatile boolean closed;

		Subscriber(EnvironmentKey environment, SseEmitter emitter) {
			this.environment = environment;
			this.emitter = emitter;
		}
	}

	private final Clock clock;

	private final long timeoutMillis;

	private final int maxSubscribersPerEnvironment;

	private final Map<EnvironmentKey, Set<Subscriber>> subscribersByEnvironment = new EnumMap<>(EnvironmentKey.class);

	private final Map<EnvironmentKey, AtomicInteger> countsByEnvironment = new EnumMap<>(EnvironmentKey.class);

	private final ExecutorService dispatcher;

	private volatile boolean running;

	public FlagStreamService(Clock clock, @Value("${featureflag.stream.timeout-ms:1800000}") long timeoutMillis,
			@Value("${featureflag.stream.max-subscribers-per-environment:1000}") int maxSubscribersPerEnvironment) {
		this.clock = clock;
		this.timeoutMillis = timeoutMillis;
		this.maxSubscribersPerEnvironment = maxSubscribersPerEnvironment;
		this.dispatcher = Executors.newFixedThreadPool(DISPATCH_THREADS, daemonThreads());

		for (EnvironmentKey environment : EnvironmentKey.values()) {
			subscribersByEnvironment.put(environment, ConcurrentHashMap.newKeySet());
			countsByEnvironment.put(environment, new AtomicInteger());
		}
	}

	/** @throws ApiException 503 STREAM_CAPACITY when the environment already has its maximum number of subscribers */
	public SseEmitter subscribe(EnvironmentKey environment) {
		AtomicInteger count = countsByEnvironment.get(environment);

		if (count.incrementAndGet() > maxSubscribersPerEnvironment) {
			count.decrementAndGet();

			throw new ApiException(SERVICE_UNAVAILABLE, "STREAM_CAPACITY",
					"This environment already has the maximum number of live update connections. Try again shortly.");
		}

		Subscriber subscriber = new Subscriber(environment, new SseEmitter(timeoutMillis));
		subscribersByEnvironment.get(environment).add(subscriber);

		subscriber.emitter.onCompletion(() -> remove(subscriber));
		subscriber.emitter.onError(exception -> remove(subscriber));
		subscriber.emitter.onTimeout(() -> {
			remove(subscriber);
			subscriber.emitter.complete();
		});

		return subscriber.emitter;
	}

	public int subscriberCount(EnvironmentKey environment) {
		return countsByEnvironment.get(environment).get();
	}

	public void publishFlagChanged(EnvironmentKey environment, String flagKey, String action) {
		publish(environment, "flag-changed", Map.of("flagKey", flagKey, "environment", environment.name(), "action", action,
				"changedAt", clock.instant().toString()));
	}

	/** Segments are environment-scoped, so only that environment's subscribers need to refresh their segment store. */
	public void publishSegmentChanged(EnvironmentKey environment, String segmentKey, String action) {
		publish(environment, "segment-changed", Map.of("segmentKey", segmentKey, "environment", environment.name(),
				"action", action, "changedAt", clock.instant().toString()));
	}

	/** The mobile application registry is global, so every environment's subscribers hear about it. */
	public void publishApplicationChanged(String applicationKey, String action) {
		for (EnvironmentKey environment : EnvironmentKey.values()) {
			publish(environment, "application-changed", Map.of("applicationKey", applicationKey, "environment",
					environment.name(), "action", action, "changedAt", clock.instant().toString()));
		}
	}

	/** A comment line every interval keeps idle connections from being closed by proxies and finds dead ones. */
	@Scheduled(fixedDelayString = "${featureflag.stream.heartbeat-interval-ms:15000}")
	public void sendHeartbeats() {
		for (EnvironmentKey environment : EnvironmentKey.values()) {
			publish(environment, StreamEvent.heartbeat());
		}
	}

	/** Completes every open stream so a graceful shutdown is not held up waiting for clients to leave. */
	public void closeAllSubscribers() {
		for (Set<Subscriber> subscribers : subscribersByEnvironment.values()) {
			for (Subscriber subscriber : subscribers) {
				if (remove(subscriber)) {
					completeInBackground(subscriber);
				}
			}
		}
	}

	@PreDestroy
	public void shutdown() {
		closeAllSubscribers();
		dispatcher.shutdown();
	}

	@Override
	public void start() {
		running = true;
	}

	@Override
	public void stop() {
		closeAllSubscribers();
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/** Same phase as the web server's graceful shutdown, so streams are closed while it waits for requests to finish. */
	@Override
	public int getPhase() {
		return SmartLifecycle.DEFAULT_PHASE;
	}

	private void publish(EnvironmentKey environment, String eventName, Map<String, Object> payload) {
		publish(environment, new StreamEvent(eventName, payload));
	}

	private void publish(EnvironmentKey environment, StreamEvent event) {
		for (Subscriber subscriber : subscribersByEnvironment.get(environment)) {
			if (subscriber.pending.offer(event)) {
				scheduleDrain(subscriber);
			} else {
				LOG.debug("Dropping a slow {} stream subscriber whose queue is full", environment);

				if (remove(subscriber)) {
					completeInBackground(subscriber);
				}
			}
		}
	}

	private void scheduleDrain(Subscriber subscriber) {
		if (!subscriber.draining.compareAndSet(false, true)) {
			return;
		}

		try {
			dispatcher.execute(() -> drain(subscriber));
		} catch (RejectedExecutionException exception) {
			subscriber.draining.set(false);
			remove(subscriber);
		}
	}

	private void drain(Subscriber subscriber) {
		try {
			StreamEvent event = subscriber.pending.poll();

			while (event != null && !subscriber.closed) {
				send(subscriber, event);
				event = subscriber.pending.poll();
			}
		} finally {
			subscriber.draining.set(false);

			if (!subscriber.closed && !subscriber.pending.isEmpty()) {
				scheduleDrain(subscriber);
			}
		}
	}

	private void send(Subscriber subscriber, StreamEvent event) {
		try {
			subscriber.emitter.send(event.toBuilder());
		} catch (IOException | IllegalStateException exception) {
			LOG.debug("Removed a dead {} stream subscriber after a failed send: {}", subscriber.environment,
					exception.toString());

			if (remove(subscriber)) {
				completeInBackground(subscriber);
			}
		}
	}

	/** True only for the call that actually unregistered the subscriber, so its count is released exactly once. */
	private boolean remove(Subscriber subscriber) {
		subscriber.closed = true;

		if (!subscribersByEnvironment.get(subscriber.environment).remove(subscriber)) {
			return false;
		}

		countsByEnvironment.get(subscriber.environment).decrementAndGet();

		return true;
	}

	/** Completing takes the emitter's lock, which a stalled send holds, so it must never run on a publishing thread. */
	private void completeInBackground(Subscriber subscriber) {
		try {
			dispatcher.execute(() -> complete(subscriber));
		} catch (RejectedExecutionException exception) {
			complete(subscriber);
		}
	}

	private void complete(Subscriber subscriber) {
		try {
			subscriber.emitter.complete();
		} catch (RuntimeException exception) {
			LOG.debug("A {} stream subscriber was already closed", subscriber.environment);
		}
	}

	private static ThreadFactory daemonThreads() {
		AtomicInteger sequence = new AtomicInteger();

		return task -> {
			Thread thread = new Thread(task, "flag-stream-dispatch-" + sequence.incrementAndGet());
			thread.setDaemon(true);

			return thread;
		};
	}
}
