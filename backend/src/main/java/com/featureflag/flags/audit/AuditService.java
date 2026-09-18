package com.featureflag.flags.audit;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class AuditService {

	private final AuditRepository auditRepository;

	public AuditService(AuditRepository auditRepository) {
		this.auditRepository = auditRepository;
	}

	public void record(String flagKey, AuditAction action, String changedBy, String summary) {
		auditRepository.save(new AuditEvent(flagKey, action, changedBy, summary));
	}

	public List<AuditEvent> history(String flagKey) {
		return auditRepository.findByFlagKeyOrderByChangedAtDesc(flagKey);
	}
}
