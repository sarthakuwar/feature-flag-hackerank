package com.featureflag.flags.metrics;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.featureflag.flags.EnvironmentKey;

/** A custom conversion event (purchase, signup, time-on-page, ...) attributed to a flag/variation/user. */
@Document("flag_conversion_events")
public class ConversionEvent {

	@Id
	private String id;

	private String flagKey;

	private EnvironmentKey environment;

	private String variationKey;

	private String userId;

	private String eventType;

	private Double value;

	@CreatedDate
	private Instant occurredAt;

	public ConversionEvent() {
	}

	public ConversionEvent(String flagKey, EnvironmentKey environment, String variationKey, String userId, String eventType,
			Double value) {
		this.flagKey = flagKey;
		this.environment = environment;
		this.variationKey = variationKey;
		this.userId = userId;
		this.eventType = eventType;
		this.value = value;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getFlagKey() {
		return flagKey;
	}

	public void setFlagKey(String flagKey) {
		this.flagKey = flagKey;
	}

	public EnvironmentKey getEnvironment() {
		return environment;
	}

	public void setEnvironment(EnvironmentKey environment) {
		this.environment = environment;
	}

	public String getVariationKey() {
		return variationKey;
	}

	public void setVariationKey(String variationKey) {
		this.variationKey = variationKey;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getEventType() {
		return eventType;
	}

	public void setEventType(String eventType) {
		this.eventType = eventType;
	}

	public Double getValue() {
		return value;
	}

	public void setValue(Double value) {
		this.value = value;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public void setOccurredAt(Instant occurredAt) {
		this.occurredAt = occurredAt;
	}
}
