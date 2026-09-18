package com.featureflag.shared;

import java.util.Map;

public class ValidationException extends ApiException {

	private static final int STATUS = 400;

	public ValidationException(Map<String, Object> details) {
		super(STATUS, "VALIDATION_ERROR", "Request validation failed.", details);
	}
}
