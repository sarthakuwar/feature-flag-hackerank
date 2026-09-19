package com.featureflag.shared;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * The validated {@code page} (1-based) and {@code limit} query parameters of a paged list route. Parsing
 * takes the raw strings so a non-numeric value becomes a 400 {@code VALIDATION_ERROR} on the field
 * instead of a framework type-mismatch error.
 */
public record PageParams(int page, int limit) {

	public static final int DEFAULT_PAGE = 1;

	public static final int DEFAULT_LIMIT = 50;

	public static final int MAX_LIMIT = 200;

	/** A missing (or empty) value falls back to its default; every invalid value is reported before throwing. */
	public static PageParams parse(String page, String limit) {
		FieldErrors errors = new FieldErrors();
		int parsedPage = parseInt(page, "page", DEFAULT_PAGE, Integer.MAX_VALUE, errors);
		int parsedLimit = parseInt(limit, "limit", DEFAULT_LIMIT, MAX_LIMIT, errors);
		errors.throwIfAny();

		return new PageParams(parsedPage, parsedLimit);
	}

	public Pageable toPageable(Sort sort) {
		return PageRequest.of(page - 1, limit, sort);
	}

	private static int parseInt(String raw, String field, int defaultValue, int max, FieldErrors errors) {
		if (raw == null || raw.isBlank()) {
			return defaultValue;
		}

		int value;

		try {
			value = Integer.parseInt(raw.trim());
		} catch (NumberFormatException exception) {
			errors.add(field, "Must be a whole number.");

			return defaultValue;
		}

		if (value < 1) {
			errors.add(field, "Must be at least 1.");
		} else if (value > max) {
			errors.add(field, "Must be at most " + max + ".");
		}

		return value;
	}
}
