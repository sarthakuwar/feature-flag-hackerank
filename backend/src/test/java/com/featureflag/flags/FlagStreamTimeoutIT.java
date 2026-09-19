package com.featureflag.flags;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** The stream timeout is configurable and ends a connection cleanly, so the browser reconnects and resyncs. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "featureflag.stream.timeout-ms=500")
@ActiveProfiles("test")
class FlagStreamTimeoutIT {

	@LocalServerPort
	private int port;

	@Autowired
	private FlagStreamService flagStreamService;

	@Test
	void aStreamEndsCleanlyOnceItsTimeoutElapsesAndReleasesItsSlot() {
		SseTestClient stream = SseTestClient.open(port, flagStreamService, EnvironmentKey.PRODUCTION);

		stream.awaitEnd();

		SseTestClient.awaitSubscribers(flagStreamService, EnvironmentKey.PRODUCTION, 0);
		assertThat(stream.lines()).noneMatch(line -> line.contains("error"));
	}
}
