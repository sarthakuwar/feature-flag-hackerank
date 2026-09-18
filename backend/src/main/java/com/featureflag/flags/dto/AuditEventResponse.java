package com.featureflag.flags.dto;

import java.time.Instant;

import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditEvent;

public record AuditEventResponse(String id, String flagKey, AuditAction action, String changedBy, String summary,
		Instant changedAt) {

	public static AuditEventResponse from(AuditEvent event) {
		return new AuditEventResponse(event.getId(), event.getFlagKey(), event.getAction(), event.getChangedBy(),
				event.getSummary(), event.getChangedAt());
	}
}
