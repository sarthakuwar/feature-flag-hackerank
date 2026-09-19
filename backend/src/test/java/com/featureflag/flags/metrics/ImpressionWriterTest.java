package com.featureflag.flags.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.EvaluationReason;
import com.featureflag.shared.MutableClock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** The writer is never started here, so flush() and stop() are the only drains and every assertion is deterministic. */
@ExtendWith(MockitoExtension.class)
class ImpressionWriterTest {

	@Mock
	private ImpressionRepository impressionRepository;

	private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	private final Logger writerLogger = (Logger) LoggerFactory.getLogger(ImpressionWriter.class);

	@BeforeEach
	void captureLogs() {
		logs.start();
		writerLogger.addAppender(logs);
	}

	@AfterEach
	void releaseLogs() {
		writerLogger.detachAppender(logs);
	}

	@Test
	void flushPersistsEveryQueuedImpressionInOneBatch() {
		ImpressionWriter writer = new ImpressionWriter(impressionRepository, clock, 100);

		writer.enqueue(impression("user-1"));
		writer.enqueue(impression("user-2"));
		writer.flush();

		ArgumentCaptor<List<ImpressionEvent>> captor = ArgumentCaptor.forClass(List.class);
		verify(impressionRepository).insert(captor.capture());
		assertThat(captor.getValue()).extracting(ImpressionEvent::getUserId).containsExactly("user-1", "user-2");
	}

	@Test
	void flushOfAnEmptyQueueWritesNothing() {
		new ImpressionWriter(impressionRepository, clock, 100).flush();

		verify(impressionRepository, never()).insert(anyList());
	}

	@Test
	void aLargeBacklogIsWrittenInBoundedBatches() {
		ImpressionWriter writer = new ImpressionWriter(impressionRepository, clock, 2000);

		for (int index = 0; index < 1200; index++) {
			writer.enqueue(impression("user-" + index));
		}

		writer.flush();

		ArgumentCaptor<List<ImpressionEvent>> captor = ArgumentCaptor.forClass(List.class);
		verify(impressionRepository, times(3)).insert(captor.capture());
		assertThat(captor.getAllValues()).extracting(List::size).containsExactly(500, 500, 200);
	}

	@Test
	void anOverflowingQueueDropsTheExtraImpressionsWithoutThrowing() {
		ImpressionWriter writer = new ImpressionWriter(impressionRepository, clock, 2);

		assertThatCode(() -> {
			for (int index = 0; index < 5; index++) {
				writer.enqueue(impression("user-" + index));
			}
		}).doesNotThrowAnyException();

		assertThat(writer.droppedCount()).isEqualTo(3);

		writer.flush();

		ArgumentCaptor<List<ImpressionEvent>> captor = ArgumentCaptor.forClass(List.class);
		verify(impressionRepository).insert(captor.capture());
		assertThat(captor.getValue()).extracting(ImpressionEvent::getUserId).containsExactly("user-0", "user-1");
	}

	@Test
	void dropWarningsAreRateLimited() {
		ImpressionWriter writer = new ImpressionWriter(impressionRepository, clock, 1);
		writer.enqueue(impression("user-0"));

		writer.enqueue(impression("dropped-1"));
		writer.enqueue(impression("dropped-2"));
		assertThat(warnings()).hasSize(1);

		clock.advance(Duration.ofSeconds(11));
		writer.enqueue(impression("dropped-3"));

		assertThat(warnings()).hasSize(2);
		assertThat(writer.droppedCount()).isEqualTo(3);
	}

	@Test
	void aRepositoryFailureIsLoggedAndNeverPropagates() {
		when(impressionRepository.insert(anyList())).thenThrow(new IllegalStateException("mongo is down"));
		ImpressionWriter writer = new ImpressionWriter(impressionRepository, clock, 100);
		writer.enqueue(impression("user-1"));

		assertThatCode(writer::flush).doesNotThrowAnyException();

		assertThat(warnings()).hasSize(1);
		assertThat(warnings().get(0).getThrowableProxy().getMessage()).isEqualTo("mongo is down");
	}

	@Test
	void aFailedBatchIsDiscardedSoLaterImpressionsStillFlow() {
		when(impressionRepository.insert(anyList())).thenThrow(new IllegalStateException("mongo is down"))
				.thenReturn(List.of());
		ImpressionWriter writer = new ImpressionWriter(impressionRepository, clock, 100);
		writer.enqueue(impression("lost"));
		writer.flush();

		writer.enqueue(impression("kept"));
		writer.flush();

		ArgumentCaptor<List<ImpressionEvent>> captor = ArgumentCaptor.forClass(List.class);
		verify(impressionRepository, times(2)).insert(captor.capture());
		assertThat(captor.getAllValues().get(1)).extracting(ImpressionEvent::getUserId).containsExactly("kept");
	}

	@Test
	void stoppingTheWriterDrainsWhatIsStillQueued() throws InterruptedException {
		ImpressionWriter writer = new ImpressionWriter(impressionRepository, clock, 100);
		writer.enqueue(impression("user-1"));
		writer.enqueue(impression("user-2"));

		writer.stop();

		ArgumentCaptor<List<ImpressionEvent>> captor = ArgumentCaptor.forClass(List.class);
		verify(impressionRepository).insert(captor.capture());
		assertThat(captor.getValue()).hasSize(2);
	}

	@Test
	void theBackgroundWorkerDrainsWithoutAnExplicitFlush() throws InterruptedException {
		ImpressionWriter writer = new ImpressionWriter(impressionRepository, clock, 100);
		writer.start();

		try {
			writer.enqueue(impression("user-1"));

			verify(impressionRepository, timeout(5000)).insert(anyList());
		} finally {
			writer.stop();
		}
	}

	private List<ILoggingEvent> warnings() {
		return logs.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
	}

	private ImpressionEvent impression(String userId) {
		return new ImpressionEvent("flag-a", EnvironmentKey.PRODUCTION, "a", userId, 1, EvaluationReason.EXPERIMENT_ASSIGNED, true);
	}
}
