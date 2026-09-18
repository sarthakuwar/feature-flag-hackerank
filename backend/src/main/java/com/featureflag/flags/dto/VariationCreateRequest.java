package com.featureflag.flags.dto;

import com.featureflag.flags.PayloadType;

public record VariationCreateRequest(String key, String name, String value, Integer weight, PayloadType payloadType,
		String payloadValue, String changedBy) {

	public VariationCreateRequest(String key, String name, String value, String changedBy) {
		this(key, name, value, null, null, null, changedBy);
	}
}
