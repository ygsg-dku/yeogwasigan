package com.capstone.yeogwasigan.web;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.gateway.AuditEvent;
import com.capstone.yeogwasigan.gateway.GatewayRequest;
import com.capstone.yeogwasigan.gateway.GatewayService;

/**
 * 반출 게이트웨이 API (설계안 v0.2의 API 5개).
 *
 * <pre>
 * POST /api/requests                     { log, purpose?, endpointId? } → 201 파생본 요약
 * GET  /api/requests?status=             요청 목록 (승인함)
 * GET  /api/requests/{id}                파생본 미리보기, 버려진 필드, 참고 경고, 상태, AI 답
 * POST /api/requests/{id}/decision       { approve, comment?, packageSha256 } → SENT · REJECTED · SEND_FAILED
 * GET  /api/requests/{id}/audit          감사 기록 시간순
 * GET  /api/gateway/meta                 사용자·역할, 등록된 전송 대상
 * GET  /api/gateway/me                   로그인한 사람과 역할
 * </pre>
 * 사용자는 로그인한 사람이다({@link SecurityConfig}).
 */
@RestController
@RequestMapping("/api")
public class GatewayController {

    private static final int PREVIEW_RECORDS = 50;

    private final GatewayService gateway;

    public GatewayController(GatewayService gateway) {
        this.gateway = gateway;
    }

    public record CreateBody(String log, String purpose, String endpointId) {
    }

    public record DecisionBody(boolean approve, String comment, String packageSha256) {
    }

    @GetMapping("/gateway/meta")
    public Map<String, Object> meta() {
        return Map.of("users", gateway.users(), "endpoints", gateway.endpoints());
    }

    @GetMapping("/gateway/me")
    public Map<String, Object> me(Principal principal) {
        return Map.of("user", principal.getName(), "roles", gateway.roles(principal.getName()));
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(Principal principal,
                                      @RequestBody CreateBody body) {
        return summary(gateway.create(principal.getName(), body.log(), body.purpose(), body.endpointId()));
    }

    @GetMapping("/requests")
    public List<Map<String, Object>> list(Principal principal,
                                          @RequestParam(required = false) GatewayRequest.Status status) {
        return gateway.list(principal.getName(), status).stream().map(GatewayController::summary).toList();
    }

    @GetMapping("/requests/{id}")
    public Map<String, Object> detail(Principal principal,
                                      @PathVariable String id) {
        GatewayRequest r = gateway.get(principal.getName(), id);
        Map<String, Object> m = summary(r);
        List<Map<String, Object>> payload = r.getPayload();
        m.put("preview", LogFormat.serialize(payload.subList(0, Math.min(PREVIEW_RECORDS, payload.size()))));
        m.put("previewRecords", Math.min(PREVIEW_RECORDS, payload.size()));
        m.put("droppedFields", r.getDroppedFields());
        m.put("answer", r.getAnswer());
        m.put("answerMode", r.getAnswerMode());
        m.put("model", r.getModel());
        m.put("comment", r.getComment());
        m.put("payloadPurged", r.isPayloadPurged());
        m.put("payloadExpiresAt", r.getPayloadExpiresAt());
        return m;
    }

    @PostMapping("/requests/{id}/decision")
    public Map<String, Object> decide(Principal principal,
                                      @PathVariable String id, @RequestBody DecisionBody body) {
        return detail(principal, gateway.decide(principal.getName(), id, body.approve(), body.comment(), body.packageSha256()).getId());
    }

    @GetMapping("/requests/{id}/audit")
    public List<AuditEvent> audit(Principal principal,
                                                 @PathVariable String id) {
        return gateway.audit(principal.getName(), id);
    }

    private static Map<String, Object> summary(GatewayRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("status", r.getStatus());
        m.put("requester", r.getRequester());
        m.put("approver", r.getApprover());
        m.put("purpose", r.getPurpose());
        m.put("endpointId", r.getEndpointId());
        m.put("createdAt", r.getCreatedAt());
        m.put("inputSha256", r.getInputSha256());
        m.put("inputRecords", r.getInputRecords());
        m.put("records", r.getPayload().size());
        m.put("fieldsBefore", r.getFieldsBefore());
        m.put("fieldsAfter", r.getFieldsAfter());
        m.put("droppedCount", r.getDroppedFields().size());
        m.put("listVersion", r.getListVersion());
        m.put("packageSha256", r.getPackageSha256());
        m.put("fingerprint", r.getFingerprint());
        m.put("warnings", r.getWarnings());
        return m;
    }
}
