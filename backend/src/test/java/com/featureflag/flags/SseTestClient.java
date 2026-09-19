package com.featureflag.flags;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;

/**
 * Reads the SSE stream of a running test server. {@link #open} returns only once the server has
 * registered the subscription (it polls {@link FlagStreamService#subscriberCount}), so a test can
 * publish immediately afterwards without sleeping and still be sure to receive the event.
 */
public final class SseTestClient implements AutoCloseable {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final long POLL_MILLIS = 10;

	private final List<String> lines = new CopyOnWriteArrayList<>();

	private final CompletableFuture<HttpResponse<Void>> response;

	private SseTestClient(int port, EnvironmentKey environment) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/api/v1/stream/" + environment.name()))
				.header("Accept", "text/event-stream").GET().build();

		this.response = HttpClient.newHttpClient().sendAsync(request,
				responseInfo -> HttpResponse.BodySubscribers.fromLineSubscriber(new Flow.Subscriber<String>() {
					@Override
					public void onSubscribe(Flow.Subscription subscription) {
						subscription.request(Long.MAX_VALUE);
					}

					@Override
					public void onNext(String item) {
						lines.add(item);
					}

					@Override
					public void onError(Throwable throwable) {
					}

					@Override
					public void onComplete() {
					}
				}));
	}

	public static SseTestClient open(int port, FlagStreamService streams, EnvironmentKey environment) {
		int before = streams.subscriberCount(environment);
		SseTestClient client = new SseTestClient(port, environment);

		awaitCondition("a new " + environment + " subscriber", () -> streams.subscriberCount(environment) > before);

		return client;
	}

	/** Waits until the server reports exactly this many subscribers, for example after a client left. */
	public static void awaitSubscribers(FlagStreamService streams, EnvironmentKey environment, int expected) {
		awaitCondition(expected + " " + environment + " subscribers", () -> streams.subscriberCount(environment) == expected);
	}

	public List<String> lines() {
		return lines;
	}

	public boolean hasLine(String needle) {
		return lines.stream().anyMatch(line -> line.contains(needle));
	}

	public void awaitLine(String needle) {
		awaitCondition("a stream line containing '" + needle + "' but saw " + lines, () -> hasLine(needle));
	}

	/** Waits until the server ended the response, which a client sees as a normal end of stream. */
	public void awaitEnd() {
		awaitCondition("the stream to end", response::isDone);
		assertThat(response.isCompletedExceptionally()).as("the stream ended cleanly").isFalse();
	}

	@Override
	public void close() {
		response.cancel(true);
	}

	private static void awaitCondition(String description, BooleanSupplier condition) {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();

		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Timed out after " + TIMEOUT + " waiting for " + description);
			}

			try {
				Thread.sleep(POLL_MILLIS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting for " + description, exception);
			}
		}
	}
}
