package com.capstone.yeogwasigan.gateway;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 요청 한 건. 원문은 없고 해시와 건수만 있다. 파생본(payload)은 JSON 문자열로 둔다. */
@Entity
@Table(name = "gateway_request")
public class RequestEntity {
    @Id String id;
    int seqNo;
    String requester;
    String purpose;
    String endpointId;
    String inputSha256;
    int inputRecords;
    String listVersion;
    String packageSha256;
    int fieldsBefore;
    int fieldsAfter;
    @Column(columnDefinition = "TEXT") String payloadJson;
    @Column(columnDefinition = "TEXT") String droppedFieldsJson;
    @Column(columnDefinition = "TEXT") String warningsJson;
    Instant createdAt;
    String status;
    String approver;
    @Column(columnDefinition = "TEXT") String decisionComment;
    String fingerprint;
    @Column(columnDefinition = "TEXT") String answer;
    String answerMode;
    String model;

    protected RequestEntity() {
    }
}