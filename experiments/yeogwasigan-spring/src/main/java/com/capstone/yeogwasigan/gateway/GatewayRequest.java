package com.capstone.yeogwasigan.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.capstone.yeogwasigan.core.log.LogFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * 반출 요청 하나. 원문 로그는 들고 있지 않고 해시와 레코드 수만 남긴다(D8).
 *
 * <p>상태: PREPARED(승인 대기) → APPROVED → SENT, 또는 REJECTED / SEND_FAILED 로 끝난다(D11).
 * 파생본(payload)은 보관 기간이 지나면 지워지고 해시와 감사 기록만 남는다(D12).
 */
@Entity
@Table(name = "gateway_request")
public class GatewayRequest {

    public enum Status { PREPARED, APPROVED, SENT, REJECTED, SEND_FAILED }

    public record Warning(String type, int count) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    private String id;
    private String requester;
    private String purpose;
    private String endpointId;
    private String inputSha256;
    private int inputRecords;
    private String listVersion;
    @Lob
    @Column(columnDefinition = "TEXT")
    private String payloadJson;
    private String packageSha256;
    private int fieldsBefore;
    private int fieldsAfter;
    @Lob
    @Column(columnDefinition = "TEXT")
    private String droppedFieldsJson;
    @Column(length = 1000)
    private String warningsJson;
    private Instant createdAt;
    private Instant payloadExpiresAt;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PREPARED;
    private String approver;
    @Lob
    @Column(columnDefinition = "TEXT")
    private String comment;
    private String fingerprint;
    @Lob
    @Column(columnDefinition = "TEXT")
    private String answer;
    private String answerMode;
    private String model;

    @Transient
    private List<Map<String, Object>> payloadCache;

    protected GatewayRequest() {
    }

    GatewayRequest(String id, String requester, String purpose, String endpointId, String inputSha256,
                   int inputRecords, String listVersion, List<Map<String, Object>> payload, String packageSha256,
                   int fieldsBefore, int fieldsAfter, List<String> droppedFields, List<Warning> warnings,
                   Instant createdAt, Instant payloadExpiresAt) {
        this.id = id;
        this.requester = requester;
        this.purpose = purpose;
        this.endpointId = endpointId;
        this.inputSha256 = inputSha256;
        this.inputRecords = inputRecords;
        this.listVersion = listVersion;
        this.payloadJson = LogFormat.toJson(payload);
        this.packageSha256 = packageSha256;
        this.fieldsBefore = fieldsBefore;
        this.fieldsAfter = fieldsAfter;
        this.droppedFieldsJson = LogFormat.toJson(droppedFields);
        this.warningsJson = LogFormat.toJson(warnings);
        this.createdAt = createdAt;
        this.payloadExpiresAt = payloadExpiresAt;
    }

    public String getId() { return id; }
    public String getRequester() { return requester; }
    public String getPurpose() { return purpose; }
    public String getEndpointId() { return endpointId; }
    public String getInputSha256() { return inputSha256; }
    public int getInputRecords() { return inputRecords; }
    public String getListVersion() { return listVersion; }
    public String getPackageSha256() { return packageSha256; }
    public int getFieldsBefore() { return fieldsBefore; }
    public int getFieldsAfter() { return fieldsAfter; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPayloadExpiresAt() { return payloadExpiresAt; }
    public Status getStatus() { return status; }
    public String getApprover() { return approver; }
    public String getComment() { return comment; }
    public String getFingerprint() { return fingerprint; }
    public String getAnswer() { return answer; }
    public String getAnswerMode() { return answerMode; }
    public String getModel() { return model; }

    /** 파생본. 실제로 외부로 나가는 것은 이것뿐이다. 보관 기간이 지나 지워졌으면 빈 목록. */
    public List<Map<String, Object>> getPayload() {
        if (payloadCache == null) {
            payloadCache = payloadJson == null ? List.of() : read(payloadJson, new TypeReference<>() { });
        }
        return payloadCache;
    }

    public boolean isPayloadPurged() { return payloadJson == null; }

    public List<String> getDroppedFields() { return read(droppedFieldsJson, new TypeReference<>() { }); }

    public List<Warning> getWarnings() { return read(warningsJson, new TypeReference<>() { }); }

    void approve(String approver, String fingerprint) {
        this.status = Status.APPROVED;
        this.approver = approver;
        this.fingerprint = fingerprint;
    }

    void reject(String approver, String comment) {
        this.status = Status.REJECTED;
        this.approver = approver;
        this.comment = comment;
    }

    void sent(String answer, String mode, String model) {
        this.status = Status.SENT;
        this.answer = answer;
        this.answerMode = mode;
        this.model = model;
    }

    void sendFailed(String reason) {
        this.status = Status.SEND_FAILED;
        this.comment = reason;
    }

    void purgePayload() {
        this.payloadJson = null;
        this.payloadCache = null;
    }

    private static <T> T read(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("저장된 요청을 읽을 수 없습니다: " + e.getMessage(), e);
        }
    }
}
