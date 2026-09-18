package com.featureflag.shared;

import java.util.Map;

public class ApiException extends RuntimeException {

	private final int statusCode;

	private final String code;

	private final Map<String, Object> details;

	public ApiException(int statusCode, String code, String message) {
		this(statusCode, code, message, null);
	}

	public ApiException(int statusCode, String code, String message, Map<String, Object> details) {
		super(message);
		this.statusCode = statusCode;
		this.code = code;
		this.details = details;
	}

	public int getStatusCode() {
		return statusCode;
	}

	public String getCode() {
		return code;
	}

	public Map<String, Object> getDetails() {
		return details;
	}
}
