package com.featureflag.flags.audit;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("flag_audit_events")
public class AuditEvent {

	@Id
	private String id;

	private String flagKey;

	private AuditAction action;

	private String changedBy;

	private String summary;

	@CreatedDate
	private Instant changedAt;

	public AuditEvent() {
	}

	public AuditEvent(String flagKey, AuditAction action, String changedBy, String summary) {
		this.flagKey = flagKey;
		this.action = action;
		this.changedBy = changedBy;
		this.summary = summary;
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

	public AuditAction getAction() {
		return action;
	}

	public void setAction(AuditAction action) {
		this.action = action;
	}

	public String getChangedBy() {
		return changedBy;
	}

	public void setChangedBy(String changedBy) {
		this.changedBy = changedBy;
	}

	public String getSummary() {
		return summary;
	}

	public void setSummary(String summary) {
		this.summary = summary;
	}

	public Instant getChangedAt() {
		return changedAt;
	}

	public void setChangedAt(Instant changedAt) {
		this.changedAt = changedAt;
	}
}
