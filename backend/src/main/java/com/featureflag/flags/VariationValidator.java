package com.featureflag.flags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.shared.FieldErrors;
import com.featureflag.shared.Validation;

/** Write-time checks for a variation's fields, payload, and (at create time) the whole list. */
final class VariationValidator {

	/** Rejects anything after the first JSON value, so a payload like {@code {} x} is not valid JSON. */
	private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	private VariationValidator() {
	}

	static List<Variation> validateAll(List<VariationRequest> requested, FlagType type, FieldErrors errors) {
		List<Variation> variations = new ArrayList<>();

		if (requested == null || requested.size() < 2) {
			errors.add("variations", "At least two variations are required.");

			return variations;
		}

		if (type == FlagType.BOOLEAN && requested.size() != 2) {
			errors.add("variations", "Boolean flags must have exactly two variations.");
		}

		Set<String> seenKeys = new HashSet<>();

		for (int index = 0; index < requested.size(); index++) {
			VariationRequest variationRequest = requested.get(index);
			String field = "variations[" + index + "]";
			Validation.requireText(variationRequest.key(), field + ".key", errors);
			Validation.requireText(variationRequest.name(), field + ".name", errors);

			if (variationRequest.value() == null) {
				errors.add(field + ".value", "Variation value is required.");
			}

			if (variationRequest.key() != null && !seenKeys.add(variationRequest.key())) {
				errors.add(field + ".key", "Variation keys must be unique within a flag.");
			}

			validateWeightRange(variationRequest.weight(), field + ".weight", errors);
			validatePayload(variationRequest.payloadType(), variationRequest.payloadValue(), field + ".payloadValue", errors);

			variations.add(new Variation(variationRequest.key(), variationRequest.name(), variationRequest.value(),
					variationRequest.weight(), variationRequest.payloadType(), variationRequest.payloadValue()));
		}

		VariationWeights.assign(variations, errors);

		return variations;
	}

	static void validateWeightRange(Integer weight, String field, FieldErrors errors) {
		if (weight != null && (weight < 0 || weight > 100)) {
			errors.add(field, "Weight must be between 0 and 100.");
		}
	}

	static void validatePayload(PayloadType payloadType, String payloadValue, String field, FieldErrors errors) {
		if (payloadType == null) {
			return;
		}

		if (payloadValue == null || payloadValue.isBlank()) {
			errors.add(field, "Payload value is required when a payload type is set.");

			return;
		}

		switch (payloadType) {
			case NUMBER -> {
				try {
					Double.parseDouble(payloadValue);
				} catch (NumberFormatException exception) {
					errors.add(field, "Payload value must be a valid number.");
				}
			}
			case JSON -> {
				try {
					JSON.readTree(payloadValue);
				} catch (JsonProcessingException exception) {
					errors.add(field, "Payload value must be valid JSON.");
				}
			}
			case CSV -> {
				boolean hasNonBlankToken = Arrays.stream(payloadValue.split(",")).anyMatch(token -> !token.isBlank());

				if (!hasNonBlankToken) {
					errors.add(field, "Payload value must contain at least one comma-separated value.");
				}
			}
			case STRING -> {
				// Any non-blank value is valid; already checked above.
			}
		}
	}
}
