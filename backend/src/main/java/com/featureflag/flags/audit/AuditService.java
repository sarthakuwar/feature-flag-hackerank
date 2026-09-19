package com.featureflag.flags.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.featureflag.shared.PageParams;

@Service
public class AuditService {

	private static final Sort HISTORY_ORDER = Sort.by(Sort.Order.desc("changedAt"), Sort.Order.desc("id"));

	private final AuditRepository auditRepository;

	public AuditService(AuditRepository auditRepository) {
		this.auditRepository = auditRepository;
	}

	public void record(String flagKey, AuditAction action, String changedBy, String summary) {
		auditRepository.save(new AuditEvent(flagKey, action, changedBy, summary));
	}

	/** Newest first; events recorded in the same millisecond fall back to the id, which grows with insertion order. */
	public Page<AuditEvent> history(String flagKey, PageParams params) {
		return auditRepository.findByFlagKey(flagKey, params.toPageable(HISTORY_ORDER));
	}
}
