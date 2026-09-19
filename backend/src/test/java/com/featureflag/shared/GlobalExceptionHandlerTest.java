package com.featureflag.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@BeforeEach
	void captureLogs() {
		logs.start();
		logger.addAppender(logs);
	}

	@AfterEach
	void releaseLogs() {
		logger.detachAppender(logs);
	}

	@Test
	void aLostOptimisticLockIsAConflictTellingTheCallerToReload() {
		ResponseEntity<Map<String, Object>> response = handler.handleConcurrentModification();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		Map<String, Object> error = error(response);
		assertThat(error.get("code")).isEqualTo("CONFLICT");
		assertThat(error.get("message").toString()).contains("changed by someone else").contains("Reload");
	}

	@Test
	void aDuplicateKeyIsAConflictThatDoesNotLeakTheDriverMessage() {
		ResponseEntity<Map<String, Object>> response = handler.handleDuplicateKey();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(error(response).get("code")).isEqualTo("DUPLICATE_KEY");
		assertThat(response.getBody().toString()).doesNotContain("E11000");
	}

	@Test
	void anUnexpectedErrorReturnsTheGenericBodyAndLogsTheStackTraceWithTheRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/flags/checkout");
		IllegalStateException failure = new IllegalStateException("secret internal detail");

		ResponseEntity<Map<String, Object>> response = handler.handleUnexpected(failure, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(error(response).get("code")).isEqualTo("INTERNAL_ERROR");
		assertThat(response.getBody().toString()).doesNotContain("secret internal detail").doesNotContain("IllegalStateException");
		assertThat(logs.list).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.ERROR);
			assertThat(event.getFormattedMessage()).contains("POST").contains("/api/v1/flags/checkout");
			assertThat(event.getThrowableProxy().getMessage()).isEqualTo("secret internal detail");
		});
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> error(ResponseEntity<Map<String, Object>> response) {
		return (Map<String, Object>) response.getBody().get("error");
	}
}
