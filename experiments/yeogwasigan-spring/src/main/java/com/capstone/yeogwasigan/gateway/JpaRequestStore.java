package com.capstone.yeogwasigan.gateway;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DB 저장소. 읽어 온 요청은 메모리에 한 번만 올려 두고 같은 객체를 돌려준다(write-through).
 * 그래야 GatewayService 의 synchronized(r) 가 같은 요청에 동시에 들어온 승인을 계속 막아 준다. 서버 한 대 기준.
 */
@Component
public class JpaRequestStore implements RequestStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> PAYLOAD = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<GatewayRequest.Warning>> WARNINGS = new TypeReference<>() { };

    private final RequestRepository requests;
    private final AuditEventRepository audits;
    private final Map<String, GatewayRequest> cache = new ConcurrentHashMap<>();
    private AtomicInteger seq;

    public JpaRequestStore(RequestRepository requests, AuditEventRepository audits) {
        this.requests = requests;
        this.audits = audits;
    }

    /** 재시작해도 번호가 겹치지 않게 DB 의 가장 큰 번호부터 이어 간다. */
    @Override
    public synchronized int nextSeq() {
        if (seq == null) {
            seq = new AtomicInteger(requests.maxSeqNo());
        }
        return seq.incrementAndGet();
    }

    @Override
    @Transactional
    public void save(GatewayRequest r) {
        synchronized (r) {
            requests.save(toEntity(r));
            List<GatewayRequest.AuditEvent> events = r.getAudit();
            int saved = (int) audits.countByRequestId(r.getId());
            for (int i = saved; i < events.size(); i++) {   // 이미 저장된 줄은 건드리지 않고 새 줄만 추가
                audits.save(AuditEventEntity.of(r.getId(), i, events.get(i)));
            }
        }
        cache.put(r.getId(), r);
    }

    @Override
    public Optional<GatewayRequest> find(String id) {
        GatewayRequest cached = cache.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        return requests.findById(id).map(this::load);
    }

    @Override
    public List<GatewayRequest> findAll() {
        return requests.findAll().stream().map(this::load).toList();
    }

    private GatewayRequest load(RequestEntity e) {
        return cache.computeIfAbsent(e.id, k -> restore(e));
    }

    private GatewayRequest restore(RequestEntity e) {
        GatewayRequest r = new GatewayRequest(e.id, e.requester, e.purpose, e.endpointId, e.inputSha256,
                e.inputRecords, e.listVersion, read(e.payloadJson, PAYLOAD), e.packageSha256,
                e.fieldsBefore, e.fieldsAfter, read(e.droppedFieldsJson, STRINGS), read(e.warningsJson, WARNINGS));
        List<GatewayRequest.AuditEvent> events = audits.findByRequestIdOrderBySeqNoAsc(e.id).stream()
                .map(AuditEventEntity::toEvent).toList();
        r.restoreState(e.createdAt, GatewayRequest.Status.valueOf(e.status), e.approver, e.decisionComment,
                e.fingerprint, e.answer, e.answerMode, e.model, events);
        return r;
    }

    private static RequestEntity toEntity(GatewayRequest r) {
        RequestEntity e = new RequestEntity();
        e.id = r.getId();
        e.seqNo = Integer.parseInt(r.getId().substring("REQ-".length()));
        e.requester = r.getRequester();
        e.purpose = r.getPurpose();
        e.endpointId = r.getEndpointId();
        e.inputSha256 = r.getInputSha256();
        e.inputRecords = r.getInputRecords();
        e.listVersion = r.getListVersion();
        e.packageSha256 = r.getPackageSha256();
        e.fieldsBefore = r.getFieldsBefore();
        e.fieldsAfter = r.getFieldsAfter();
        e.payloadJson = write(r.getPayload());
        e.droppedFieldsJson = write(r.getDroppedFields());
        e.warningsJson = write(r.getWarnings());
        e.createdAt = r.getCreatedAt();
        e.status = r.getStatus().name();
        e.approver = r.getApprover();
        e.decisionComment = r.getComment();
        e.fingerprint = r.getFingerprint();
        e.answer = r.getAnswer();
        e.answerMode = r.getAnswerMode();
        e.model = r.getModel();
        return e;
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("저장용 JSON 변환 실패", ex);
        }
    }

    private static <T> T read(String s, TypeReference<T> type) {
        try {
            return JSON.readValue(s, type);
        } catch (IOException ex) {
            throw new IllegalStateException("저장된 JSON 읽기 실패", ex);
        }
    }
}