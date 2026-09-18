package com.featureflag.flags.dto;

import com.featureflag.flags.PayloadType;

public record VariationUpdateRequest(String name, String value, Integer weight, PayloadType payloadType,
		String payloadValue, String changedBy) {

	public VariationUpdateRequest(String name, String value, String changedBy) {
		this(name, value, null, null, null, changedBy);
	}
}
