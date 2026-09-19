package com.featureflag.flags.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AuditRepository extends MongoRepository<AuditEvent, String> {

	Page<AuditEvent> findByFlagKey(String flagKey, Pageable pageable);
}
