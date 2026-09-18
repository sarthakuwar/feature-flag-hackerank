package com.featureflag.flags.dto;

import com.featureflag.flags.PayloadType;

public record VariationRequest(String key, String name, String value, Integer weight, PayloadType payloadType,
		String payloadValue) {

	public VariationRequest(String key, String name, String value) {
		this(key, name, value, null, null, null);
	}
}
