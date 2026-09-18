package com.featureflag.flags;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-app stand-in for the streaming endpoint a real SDK would hold a persistent connection to.
 * WebSockets are not available in this runtime (GUIDELINES.md), so this uses plain HTTP
 * Server-Sent Events, which spring-boot-starter-web already ships (no new dependency). Every
 * flag write broadcasts to every subscriber of the affected environment; a single process is
 * plenty to demonstrate sub-5-second propagation without a real pub/sub broker.
 */
@Service
public class FlagStreamService {

	private static final long NO_TIMEOUT = 0L;

	private final Map<EnvironmentKey, List<SseEmitter>> subscribersByEnvironment = new ConcurrentHashMap<>();

	public SseEmitter subscribe(EnvironmentKey environment) {
		SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
		List<SseEmitter> subscribers = subscribersByEnvironment.computeIfAbsent(environment,
				key -> new CopyOnWriteArrayList<>());

		subscribers.add(emitter);

		emitter.onCompletion(() -> subscribers.remove(emitter));
		emitter.onTimeout(() -> subscribers.remove(emitter));
		emitter.onError(exception -> subscribers.remove(emitter));

		return emitter;
	}

	public void publishFlagChanged(EnvironmentKey environment, String flagKey, String action) {
		List<SseEmitter> subscribers = subscribersByEnvironment.get(environment);

		if (subscribers == null || subscribers.isEmpty()) {
			return;
		}

		Map<String, Object> payload = Map.of("flagKey", flagKey, "environment", environment.name(), "action", action,
				"changedAt", Instant.now().toString());

		for (SseEmitter emitter : subscribers) {
			try {
				emitter.send(SseEmitter.event().name("flag-changed").data(payload));
			} catch (IOException | IllegalStateException exception) {
				subscribers.remove(emitter);
			}
		}
	}
}
