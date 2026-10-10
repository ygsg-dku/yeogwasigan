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
import com.capstone.yeogwasigan.gateway.WarningRules;

/**
 * 반출 게이트웨이 API (설계안 v0.2의 API 5개).
 *
 * <pre>
 * POST /api/requests                     { log, purpose?, endpointId? } → 201 파생본 요약
 * GET  /api/requests?status=             요청 목록 (승인함)
 * GET  /api/requests/{id}                파생본 미리보기, 버려진 필드, 참고 경고, 상태, AI 답
 * POST /api/requests/{id}/decision       { approve, comment?, packageSha256, falsePositive? } → SENT · REJECTED · SEND_FAILED
 *                                        높음 경고가 있으면 falsePositive=true 와 comment(사유)가 있어야 승인된다
 * POST /api/requests/{id}/send           { packageSha256 } 조건부 승인 모드에서 높음 경고가 없는 요청을 요청자가 보낸다
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
    /** 승인 화면에 보내는 높음 경고 위치의 상한. 파생본 전체를 검사하고 앞에서부터 이만큼 보인다 */
    private static final int MAX_HIGH_FINDINGS = 500;

    private final GatewayService gateway;

    public GatewayController(GatewayService gateway) {
        this.gateway = gateway;
    }

    public record CreateBody(String log, String purpose, String endpointId) {
    }

    public record DecisionBody(boolean approve, String comment, String packageSha256, boolean falsePositive) {
    }

    public record SendBody(String packageSha256) {
    }

    @GetMapping("/gateway/meta")
    public Map<String, Object> meta() {
        return Map.of("users", gateway.users(), "endpoints", gateway.endpoints(), "approvalMode", gateway.approvalMode());
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
        m.put("approvalMode", gateway.approvalMode());
        List<WarningRules.Finding> findings = WarningRules.scan(payload);
        // 높음 경고는 파생본 전체에서 위치를 보여 준다(앞 50건 미리보기에 없더라도). 값은 가려서 보낸다
        m.put("highFindings", findings.stream().filter(f -> f.level() == WarningRules.Level.HIGH).limit(MAX_HIGH_FINDINGS)
                .map(f -> finding(f, findings, payload)).toList());
        // 미리보기(앞 50건) 안에서 강조할 값. 미리보기에 이미 보이는 값이라 원문을 그대로 쓴다
        m.put("previewMarks", findings.stream().filter(f -> f.record() < PREVIEW_RECORDS)
                .map(f -> Map.of("value", f.value(), "level", f.level())).distinct().toList());
        m.put("payloadExpiresAt", r.getPayloadExpiresAt());
        return m;
    }

    @PostMapping("/requests/{id}/decision")
    public Map<String, Object> decide(Principal principal,
                                      @PathVariable String id, @RequestBody DecisionBody body) {
        return detail(principal, gateway.decide(principal.getName(), id, body.approve(), body.comment(), body.packageSha256(),
                body.falsePositive()).getId());
    }

    @PostMapping("/requests/{id}/send")
    public Map<String, Object> send(Principal principal, @PathVariable String id, @RequestBody SendBody body) {
        return detail(principal, gateway.sendWithoutApproval(principal.getName(), id, body.packageSha256()).getId());
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
        m.put("highWarnings", r.highWarnings());
        m.put("approvalRequired", r.isApprovalRequired());
        return m;
    }

    /**
     * 높음 경고 한 건: 몇 번째 기록의 어느 칸인지, 가린 값, 앞뒤 문맥.
     * 문맥에 같은 칸의 다른 경고 값이 섞여 있을 수 있어, 그 칸의 경고 값을 모두 가린 뒤 자른다.
     */
    private static Map<String, Object> finding(WarningRules.Finding f, List<WarningRules.Finding> all,
                                               List<Map<String, Object>> payload) {
        String text = String.valueOf(valueAt(payload.get(f.record()), f.field()));
        String context = "[" + f.masked() + "]";   // 칸 값을 다시 찾지 못하면(배열 속 값 등) 가린 값만 보인다
        if (f.end() <= text.length() && text.substring(f.start(), f.end()).equals(f.value())) {
            List<WarningRules.Finding> same = all.stream()
                    .filter(o -> o.record() == f.record() && o.field().equals(f.field()))
                    .sorted(java.util.Comparator.comparingInt(WarningRules.Finding::start)).toList();
            StringBuilder masked = new StringBuilder();
            int pos = 0;
            int targetStart = 0;
            int targetEnd = 0;
            for (WarningRules.Finding o : same) {
                masked.append(text, pos, o.start());
                if (o.equals(f)) {
                    targetStart = masked.length();
                }
                masked.append('[').append(o.masked()).append(']');
                if (o.equals(f)) {
                    targetEnd = masked.length();
                }
                pos = o.end();
            }
            masked.append(text.substring(pos));
            int from = Math.max(0, targetStart - 40);
            int to = Math.min(masked.length(), targetEnd + 40);
            context = (from > 0 ? "…" : "") + masked.substring(from, to) + (to < masked.length() ? "…" : "");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("record", f.record() + 1);
        m.put("field", f.field());
        m.put("type", f.type());
        m.put("masked", f.masked());
        m.put("context", context);
        return m;
    }

    private static Object valueAt(Object node, String path) {
        for (Map.Entry<?, ?> e : node instanceof Map<?, ?> map ? map.entrySet() : java.util.Set.<Map.Entry<?, ?>>of()) {
            String k = String.valueOf(e.getKey());
            if (path.equals(k)) {
                return e.getValue();
            }
            if (path.startsWith(k + ".")) {
                Object v = valueAt(e.getValue(), path.substring(k.length() + 1));
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }
}
