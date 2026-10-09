package com.capstone.yeogwasigan.gateway;

import java.time.Instant;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 감사 기록 한 줄. 추가만 되고 고치지 않는다(@Immutable). */
@Entity
@Immutable
@Table(name = "audit_event",
        uniqueConstraints = @UniqueConstraint(columnNames = {"request_id", "seq_no"}))
public class AuditEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "request_id", nullable = false) String requestId;
    @Column(name = "seq_no", nullable = false) int seqNo;
    @Column(name = "occurred_at", nullable = false) Instant occurredAt;
    String actor;
    String eventType;
    @Column(columnDefinition = "TEXT") String detail;

    protected AuditEventEntity() {
    }

    static AuditEventEntity of(String requestId, int seqNo, GatewayRequest.AuditEvent e) {
        AuditEventEntity a = new AuditEventEntity();
        a.requestId = requestId;
        a.seqNo = seqNo;
        a.occurredAt = e.at();
        a.actor = e.actor();
        a.eventType = e.type();
        a.detail = e.detail();
        return a;
    }

    GatewayRequest.AuditEvent toEvent() {
        return new GatewayRequest.AuditEvent(occurredAt, actor, eventType, detail);
    }
}