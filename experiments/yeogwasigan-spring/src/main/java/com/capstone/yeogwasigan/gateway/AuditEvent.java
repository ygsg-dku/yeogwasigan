package com.capstone.yeogwasigan.gateway;

import java.time.Instant;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 감사 기록 한 줄. 추가만 된다: {@link Immutable} 이라 저장 뒤 수정이 반영되지 않고, 지우는 API 도 없다. */
@Entity
@Immutable
@Table(name = "audit_event", indexes = @Index(columnList = "requestId"))
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String requestId;
    private Instant at;
    private String actor;
    private String type;
    @Column(length = 4000)
    private String detail;

    protected AuditEvent() {
    }

    AuditEvent(String requestId, String actor, String type, String detail) {
        this.requestId = requestId;
        this.at = Instant.now();
        this.actor = actor;
        this.type = type;
        this.detail = detail == null || detail.length() <= 4000 ? detail : detail.substring(0, 4000);
    }

    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public Instant getAt() { return at; }
    public String getActor() { return actor; }
    public String getType() { return type; }
    public String getDetail() { return detail; }
}
