package com.capstone.yeogwasigan.gateway;

import java.util.List;

import org.springframework.data.repository.Repository;

public interface AuditEventRepository extends Repository<AuditEventEntity, Long> {
    AuditEventEntity save(AuditEventEntity e);
    long countByRequestId(String requestId);
    List<AuditEventEntity> findByRequestIdOrderBySeqNoAsc(String requestId);
}