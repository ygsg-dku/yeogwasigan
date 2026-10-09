package com.capstone.yeogwasigan.gateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 반출 요청 하나. 원문 로그는 들고 있지 않고 해시와 레코드 수만 남긴다(D8).
 *
 * <p>상태: PREPARED(승인 대기) → APPROVED → SENT, 또는 REJECTED / SEND_FAILED 로 끝난다(D11).
 */
public class GatewayRequest {

    public enum Status { PREPARED, APPROVED, SENT, REJECTED, SEND_FAILED }

    public record Warning(String type, int count) {
    }

    public record AuditEvent(Instant at, String actor, String type, String detail) {
    }

    private final String id;
    private final String requester;
    private final String purpose;
    private final String endpointId;
    private final String inputSha256;
    private final int inputRecords;
    private final String listVersion;
    private final List<Map<String, Object>> payload;
    private final String packageSha256;
    private final int fieldsBefore;
    private final int fieldsAfter;
    private final List<String> droppedFields;
    private final List<Warning> warnings;
    private Instant createdAt = Instant.now();
    private final List<AuditEvent> audit = new ArrayList<>();

    private Status status = Status.PREPARED;
    private String approver;
    private String comment;
    private String fingerprint;
    private String answer;
    private String answerMode;
    private String model;

    GatewayRequest(String id, String requester, String purpose, String endpointId, String inputSha256,
                   int inputRecords, String listVersion, List<Map<String, Object>> payload, String packageSha256,
                   int fieldsBefore, int fieldsAfter, List<String> droppedFields, List<Warning> warnings) {
        this.id = id;
        this.requester = requester;
        this.purpose = purpose;
        this.endpointId = endpointId;
        this.inputSha256 = inputSha256;
        this.inputRecords = inputRecords;
        this.listVersion = listVersion;
        this.payload = payload;
        this.packageSha256 = packageSha256;
        this.fieldsBefore = fieldsBefore;
        this.fieldsAfter = fieldsAfter;
        this.droppedFields = List.copyOf(droppedFields);
        this.warnings = List.copyOf(warnings);
    }

    /** 감사 기록은 추가만 된다. 고치거나 지우는 메서드는 없다. */
    synchronized void log(String actor, String type, String detail) {
        audit.add(new AuditEvent(Instant.now(), actor, type, detail));
    }

    public String getId() { return id; }
    public String getRequester() { return requester; }
    public String getPurpose() { return purpose; }
    public String getEndpointId() { return endpointId; }
    public String getInputSha256() { return inputSha256; }
    public int getInputRecords() { return inputRecords; }
    public String getListVersion() { return listVersion; }
    /** 파생본. 실제로 외부로 나가는 것은 이것뿐이다. */
    public List<Map<String, Object>> getPayload() { return payload; }
    public String getPackageSha256() { return packageSha256; }
    public int getFieldsBefore() { return fieldsBefore; }
    public int getFieldsAfter() { return fieldsAfter; }
    public List<String> getDroppedFields() { return droppedFields; }
    public List<Warning> getWarnings() { return warnings; }
    public Instant getCreatedAt() { return createdAt; }
    public synchronized List<AuditEvent> getAudit() { return Collections.unmodifiableList(new ArrayList<>(audit)); }
    public synchronized Status getStatus() { return status; }
    public synchronized String getApprover() { return approver; }
    public synchronized String getComment() { return comment; }
    public synchronized String getFingerprint() { return fingerprint; }
    public synchronized String getAnswer() { return answer; }
    public synchronized String getAnswerMode() { return answerMode; }
    public synchronized String getModel() { return model; }

    synchronized void approve(String approver, String fingerprint) {
        this.status = Status.APPROVED;
        this.approver = approver;
        this.fingerprint = fingerprint;
    }

    synchronized void reject(String approver, String comment) {
        this.status = Status.REJECTED;
        this.approver = approver;
        this.comment = comment;
    }

    synchronized void sent(String answer, String mode, String model) {
        this.status = Status.SENT;
        this.answer = answer;
        this.answerMode = mode;
        this.model = model;
    }

    synchronized void sendFailed(String reason) {
        this.status = Status.SEND_FAILED;
        this.comment = reason;
    }

    /** DB에서 읽어 되살릴 때만 쓴다. 감사 기록은 새로 만든 빈 목록에 채워 넣는다. */
    synchronized void restoreState(Instant createdAt, Status status, String approver, String comment,
                                   String fingerprint, String answer, String answerMode, String model,
                                   List<AuditEvent> events) {
        this.createdAt = createdAt;
        this.status = status;
        this.approver = approver;
        this.comment = comment;
        this.fingerprint = fingerprint;
        this.answer = answer;
        this.answerMode = answerMode;
        this.model = model;
        this.audit.addAll(events);
    }
}
