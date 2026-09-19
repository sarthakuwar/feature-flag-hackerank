package com.featureflag.flags;

import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.featureflag.shared.ApiException;
import com.featureflag.shared.GlobalExceptionHandler;

/**
 * Refusals from the stream endpoint (such as 503 STREAM_CAPACITY). A browser subscribes with
 * {@code Accept: text/event-stream}, and the shared handler cannot write its JSON envelope for that
 * Accept value, so this handler fixes the content type to JSON. The body is still shaped by
 * {@link GlobalExceptionHandler#envelope}.
 */
@RestControllerAdvice(assignableTypes = FlagStreamController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FlagStreamExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<Map<String, Object>> handleApiException(ApiException exception) {
		return ResponseEntity.status(exception.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
				.body(GlobalExceptionHandler.envelope(exception.getCode(), exception.getMessage(), exception.getDetails()));
	}
}
