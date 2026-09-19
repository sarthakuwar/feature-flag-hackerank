package com.featureflag.flags.metrics;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.EvaluationReason;

/** One SDK-style evaluation of a single flag for a single user - the "impression" a real SDK emits from isEnabled/getVariant. */
@Document("flag_impression_events")
public class ImpressionEvent {

	@Id
	private String id;

	private String flagKey;

	private EnvironmentKey environment;

	private String variationKey;

	private String userId;

	private int bucket;

	private EvaluationReason reason;

	/**
	 * False when the user was hand-picked (individual target, or a rule not opted into the experiment)
	 * rather than randomly assigned. Defaults to true so documents written before this field existed
	 * keep counting exactly as they always did.
	 */
	private boolean includedInExperiment = true;

	@CreatedDate
	private Instant occurredAt;

	public ImpressionEvent() {
	}

	public ImpressionEvent(String flagKey, EnvironmentKey environment, String variationKey, String userId, int bucket,
			EvaluationReason reason) {
		this.flagKey = flagKey;
		this.environment = environment;
		this.variationKey = variationKey;
		this.userId = userId;
		this.bucket = bucket;
		this.reason = reason;
	}

	public ImpressionEvent(String flagKey, EnvironmentKey environment, String variationKey, String userId, int bucket,
			EvaluationReason reason, boolean includedInExperiment) {
		this(flagKey, environment, variationKey, userId, bucket, reason);
		this.includedInExperiment = includedInExperiment;
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

	public int getBucket() {
		return bucket;
	}

	public void setBucket(int bucket) {
		this.bucket = bucket;
	}

	public EvaluationReason getReason() {
		return reason;
	}

	public void setReason(EvaluationReason reason) {
		this.reason = reason;
	}

	public boolean isIncludedInExperiment() {
		return includedInExperiment;
	}

	public void setIncludedInExperiment(boolean includedInExperiment) {
		this.includedInExperiment = includedInExperiment;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public void setOccurredAt(Instant occurredAt) {
		this.occurredAt = occurredAt;
	}
}
