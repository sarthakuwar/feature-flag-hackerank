package com.featureflag.flags.metrics.dto;

import java.time.Instant;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.metrics.ConversionEvent;

public record ConversionEventResponse(String id, String flagKey, EnvironmentKey environment, String variationKey,
		String userId, String eventType, Double value, Instant occurredAt) {

	public static ConversionEventResponse from(ConversionEvent event) {
		return new ConversionEventResponse(event.getId(), event.getFlagKey(), event.getEnvironment(), event.getVariationKey(),
				event.getUserId(), event.getEventType(), event.getValue(), event.getOccurredAt());
	}
}
