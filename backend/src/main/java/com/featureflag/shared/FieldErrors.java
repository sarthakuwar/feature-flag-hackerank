package com.featureflag.shared;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FieldErrors {

	private final List<String> formErrors = new ArrayList<>();

	private final Map<String, List<String>> fieldErrors = new LinkedHashMap<>();

	public void add(String field, String message) {
		fieldErrors.computeIfAbsent(field, key -> new ArrayList<>()).add(message);
	}

	public void addFormError(String message) {
		formErrors.add(message);
	}

	public boolean has(String field) {
		return fieldErrors.containsKey(field);
	}

	public boolean isEmpty() {
		return formErrors.isEmpty() && fieldErrors.isEmpty();
	}

	public void throwIfAny() {
		if (isEmpty()) {
			return;
		}

		throw new ValidationException(details());
	}

	/** The {@code formErrors} and {@code fieldErrors} object a 400 {@code VALIDATION_ERROR} carries as its details. */
	public Map<String, Object> details() {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("formErrors", formErrors);
		details.put("fieldErrors", fieldErrors);

		return details;
	}
}
