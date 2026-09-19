package com.featureflag.flags.metrics;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Keeps the impression write off the evaluation request: {@link #enqueue} only offers to a bounded
 * in-memory queue and never throws, and a background worker drains the queue in batches with one
 * insert per batch. When the queue is full the impression is dropped and counted, because a lost
 * impression is not worth slowing or failing a flag evaluation. {@link #flush()} drains
 * synchronously, which is how conversions, metrics reads, shutdown and tests see queued impressions.
 */
@Component
public class ImpressionWriter {

	private static final Logger LOG = LoggerFactory.getLogger(ImpressionWriter.class);

	private static final int BATCH_SIZE = 500;

	private static final long DRAIN_INTERVAL_MILLIS = 50;

	private static final long WARNING_INTERVAL_MILLIS = 10_000;

	private static final long SHUTDOWN_WAIT_SECONDS = 5;

	private final ImpressionRepository impressionRepository;

	private final Clock clock;

	private final int capacity;

	private final BlockingQueue<ImpressionEvent> queue;

	/** Held while a batch is taken from the queue and written, so a flush never returns while one is still in flight. */
	private final ReentrantLock writeLock = new ReentrantLock();

	private final AtomicLong dropped = new AtomicLong();

	private final AtomicLong lastDropWarningAt = new AtomicLong(Long.MIN_VALUE);

	private ScheduledExecutorService worker;

	public ImpressionWriter(ImpressionRepository impressionRepository, Clock clock,
			@Value("${featureflag.impressions.queue-capacity:${IMPRESSION_QUEUE_CAPACITY:10000}}") int queueCapacity) {
		this.impressionRepository = impressionRepository;
		this.clock = clock;
		this.capacity = Math.max(1, queueCapacity);
		this.queue = new ArrayBlockingQueue<>(capacity);
	}

	@PostConstruct
	public void start() {
		worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "impression-writer");
			thread.setDaemon(true);

			return thread;
		});
		worker.scheduleWithFixedDelay(this::drainQuietly, DRAIN_INTERVAL_MILLIS, DRAIN_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
	}

	@PreDestroy
	public void stop() throws InterruptedException {
		if (worker != null) {
			worker.shutdown();
			worker.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
		}

		drain(Integer.MAX_VALUE);
	}

	public void enqueue(ImpressionEvent event) {
		if (queue.offer(event)) {
			return;
		}

		long total = dropped.incrementAndGet();
		long now = clock.millis();
		long last = lastDropWarningAt.get();

		if ((last == Long.MIN_VALUE || now - last >= WARNING_INTERVAL_MILLIS) && lastDropWarningAt.compareAndSet(last, now)) {
			LOG.warn("Impression queue is full (capacity {}); dropped {} impressions so far.", capacity, total);
		}
	}

	/** Writes every impression that was queued before this call; failures are logged, never thrown. */
	public void flush() {
		drain(queue.size());
	}

	public long droppedCount() {
		return dropped.get();
	}

	private void drainQuietly() {
		try {
			drain(Integer.MAX_VALUE);
		} catch (RuntimeException exception) {
			LOG.warn("Impression drain failed.", exception);
		}
	}

	private void drain(int limit) {
		writeLock.lock();

		try {
			int remaining = limit;

			while (remaining > 0) {
				List<ImpressionEvent> batch = new ArrayList<>();
				queue.drainTo(batch, Math.min(BATCH_SIZE, remaining));

				if (batch.isEmpty()) {
					return;
				}

				remaining -= batch.size();
				write(batch);
			}
		} finally {
			writeLock.unlock();
		}
	}

	private void write(List<ImpressionEvent> batch) {
		try {
			impressionRepository.insert(batch);
		} catch (RuntimeException exception) {
			LOG.warn("Failed to write a batch of {} impressions; the batch is discarded.", batch.size(), exception);
		}
	}
}
