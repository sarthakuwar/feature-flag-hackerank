package com.featureflag.shared;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final int INTERNAL_STATUS = 500;

	private static final String INTERNAL_CODE = "INTERNAL_ERROR";

	private static final String INTERNAL_MESSAGE = "An unexpected error occurred.";

	private static final int BAD_REQUEST_STATUS = 400;

	private static final int NOT_FOUND_STATUS = 404;

	private static final int SERVICE_UNAVAILABLE_STATUS = 503;

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<Map<String, Object>> handleApiException(ApiException exception) {
		return ResponseEntity.status(exception.getStatusCode())
				.body(envelope(exception.getCode(), exception.getMessage(), exception.getDetails()));
	}

	/** A body that isn't valid JSON, or has a wrong-typed field or an unknown enum value, is the caller's mistake, not a server fault. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> handleUnreadableBody() {
		return ResponseEntity.status(BAD_REQUEST_STATUS).body(envelope("MALFORMED_REQUEST",
				"Request body is missing or malformed. Check the JSON syntax, field types and enum values.", null));
	}

	@ExceptionHandler(AsyncRequestTimeoutException.class)
	public ResponseEntity<Void> handleAsyncTimeout() {
		// A client disconnecting from a long-lived response (an SSE stream, for example) surfaces
		// here with the response already committed; writing a JSON body would itself fail.
		return ResponseEntity.status(SERVICE_UNAVAILABLE_STATUS).build();
	}

	@ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class,
			HttpRequestMethodNotSupportedException.class })
	public ResponseEntity<Map<String, Object>> handleMissingRoute(HttpServletRequest request) {
		String message = "Route " + request.getMethod() + " " + request.getRequestURI() + " was not found.";

		return ResponseEntity.status(NOT_FOUND_STATUS).body(envelope("NOT_FOUND", message, null));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception) {
		System.err.println("Unhandled error: " + exception);

		return ResponseEntity.status(INTERNAL_STATUS).body(envelope(INTERNAL_CODE, INTERNAL_MESSAGE, null));
	}

	public static Map<String, Object> envelope(String code, String message, Map<String, Object> details) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", code);
		error.put("message", message);

		if (details != null) {
			error.put("details", details);
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", error);

		return body;
	}
}
