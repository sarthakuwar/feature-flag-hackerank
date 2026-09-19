package com.featureflag.flags.targeting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.featureflag.flags.targeting.dto.ClauseRequest;
import com.featureflag.shared.FieldErrors;

/**
 * Write-time validation for clauses, shared by flag targeting rules and segment rules. Everything
 * evaluation would otherwise have to defend against at read time (a regex that doesn't compile, a
 * segment that doesn't exist, a number that isn't one) is rejected here, so a saved clause is one
 * the matcher can act on.
 */
final class ClauseValidator {

	private ClauseValidator() {
	}

	/**
	 * @param segmentLookup resolves a segment key to the segment in the rule's environment; null
	 *        means segment clauses are not allowed here (segment rules cannot nest segments)
	 */
	static List<Clause> validate(List<ClauseRequest> requests, String field, Function<String, Optional<Segment>> segmentLookup,
			FieldErrors errors) {
		List<Clause> clauses = new ArrayList<>();

		if (requests == null || requests.isEmpty()) {
			errors.add(field, "At least one condition is required.");

			return clauses;
		}

		for (int index = 0; index < requests.size(); index++) {
			ClauseRequest request = requests.get(index);
			String clauseField = field + "[" + index + "]";

			if (request == null) {
				errors.add(clauseField, "Condition is required.");
				continue;
			}

			clauses.add(validateOne(request, clauseField, segmentLookup, errors));
		}

		return clauses;
	}

	private static Clause validateOne(ClauseRequest request, String field,
			Function<String, Optional<Segment>> segmentLookup, FieldErrors errors) {
		String contextKind = TargetingValidation.normalizeKind(request.contextKind());
		TargetingValidation.validateKind(contextKind, field + ".contextKind", errors);

		ClauseOperator operator = request.operator();

		if (operator == null) {
			errors.add(field + ".operator", "Operator is required.");

			return new Clause(contextKind, request.attribute(), null, request.values(), request.negate());
		}

		if (operator == ClauseOperator.APP_VERSION_SUPPORTED) {
			if (!TargetingLimits.APPLICATION_KIND.equals(contextKind)) {
				errors.add(field + ".contextKind",
						"App version support can only be checked on the " + TargetingLimits.APPLICATION_KIND + " context kind.");
			}

			return new Clause(contextKind, null, operator, List.of(), request.negate());
		}

		Set<String> values = validateValues(request, field, errors);

		if (operator == ClauseOperator.IN_SEGMENT) {
			validateSegmentReferences(values, contextKind, field, segmentLookup, errors);

			return new Clause(contextKind, null, operator, new ArrayList<>(values), request.negate());
		}

		if (request.attribute() == null || request.attribute().isBlank()) {
			errors.add(field + ".attribute", "Attribute is required.");
		} else if (request.attribute().length() > TargetingLimits.MAX_VALUE_LENGTH) {
			errors.add(field + ".attribute", "Attribute must be at most " + TargetingLimits.MAX_VALUE_LENGTH + " characters.");
		}

		validateOperatorValues(operator, values, field, errors);

		return new Clause(contextKind, request.attribute(), operator, new ArrayList<>(values), request.negate());
	}

	private static Set<String> validateValues(ClauseRequest request, String field, FieldErrors errors) {
		Set<String> values = new LinkedHashSet<>();

		if (request.values() == null || request.values().isEmpty()) {
			errors.add(field + ".values", "At least one value is required.");

			return values;
		}

		for (String value : request.values()) {
			if (value == null || value.isEmpty()) {
				errors.add(field + ".values", "Values cannot be empty.");
			} else if (value.length() > TargetingLimits.MAX_VALUE_LENGTH) {
				errors.add(field + ".values", "Each value must be at most " + TargetingLimits.MAX_VALUE_LENGTH + " characters.");
			} else {
				values.add(value);
			}
		}

		return values;
	}

	private static void validateSegmentReferences(Set<String> segmentKeys, String contextKind, String field,
			Function<String, Optional<Segment>> segmentLookup, FieldErrors errors) {
		if (segmentLookup == null) {
			errors.add(field + ".operator", "A segment rule cannot reference another segment.");

			return;
		}

		for (String segmentKey : segmentKeys) {
			Optional<Segment> segment = segmentLookup.apply(segmentKey);

			if (segment.isEmpty()) {
				errors.add(field + ".values", "'" + segmentKey + "' does not match a segment in this environment.");
			} else if (!segment.get().getContextKind().equals(contextKind)) {
				errors.add(field + ".values", "Segment '" + segmentKey + "' targets the '" + segment.get().getContextKind()
						+ "' context kind, not '" + contextKind + "'.");
			}
		}
	}

	private static void validateOperatorValues(ClauseOperator operator, Set<String> values, String field, FieldErrors errors) {
		for (String value : values) {
			if (operator.isNumeric() && !isFiniteNumber(value)) {
				errors.add(field + ".values", "'" + value + "' is not a valid number.");
			} else if (operator.isSemver() && SemanticVersion.parse(value).isEmpty()) {
				errors.add(field + ".values", "'" + value + "' is not a valid version.");
			} else if (operator == ClauseOperator.MATCHES) {
				validateRegex(value, field, errors);
			}
		}
	}

	private static boolean isFiniteNumber(String value) {
		try {
			return Double.isFinite(Double.parseDouble(value.trim()));
		} catch (NumberFormatException exception) {
			return false;
		}
	}

	private static void validateRegex(String value, String field, FieldErrors errors) {
		if (value.length() > TargetingLimits.MAX_REGEX_LENGTH) {
			errors.add(field + ".values", "A regular expression must be at most " + TargetingLimits.MAX_REGEX_LENGTH + " characters.");

			return;
		}

		try {
			Pattern.compile(value);
		} catch (PatternSyntaxException exception) {
			errors.add(field + ".values", "'" + value + "' is not a valid regular expression.");

			return;
		}

		RegexSafety.catastrophicReason(value).ifPresent(reason -> errors.add(field + ".values",
				"'" + value + "' can take exponential time to match: it " + reason + ". Simplify the expression."));
	}
}
