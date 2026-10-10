package com.capstone.yeogwasigan.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.capstone.yeogwasigan.core.ai.AiAnswer;
import com.capstone.yeogwasigan.core.ai.AiClient;
import com.capstone.yeogwasigan.core.config.ExperimentConstants;
import com.capstone.yeogwasigan.core.filter.FilterRegistry;
import com.capstone.yeogwasigan.core.filter.FilterResult;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.template.TemplateLoader;
import com.capstone.yeogwasigan.gateway.GatewayProperties.ApprovalMode;
import com.capstone.yeogwasigan.gateway.GatewayProperties.Role;
import com.capstone.yeogwasigan.gateway.GatewayRequest.Status;
import com.capstone.yeogwasigan.gateway.GatewayRequest.Warning;
import com.capstone.yeogwasigan.preprocess.PreprocessProperties;
import com.capstone.yeogwasigan.preprocess.Preprocessor;

/**
 * 반출 게이트웨이: 요청 만들기 → 승인 → 등록된 주소로 전송 → 감사 기록.
 *
 * <ul>
 *   <li>원문은 메모리에서 화이트리스트(담기)만 거치고 버린다. 남는 것은 입력 해시·레코드 수와 파생본(D8).</li>
 *   <li>요청자와 승인자는 달라야 한다(D5).</li>
 *   <li>참고 경고는 {@link WarningRules} 기준(높음·낮음). 높음 경고가 있는 요청을 승인하려면 승인자가 "오탐 확인"과 사유를 남겨야 한다.
 *       조건부 승인 모드에서는 높음 경고가 없는 요청을 요청자가 승인 없이 보낼 수 있다(docs/spec/14_APPROVAL_AND_WARNING.md).</li>
 *   <li>승인 지문 = SHA-256(파생본 해시 | 목록 버전 | 전송 대상). 전송 직전에 다시 계산해 다르면 보내지 않는다.</li>
 *   <li>등록된 전송 대상만 쓸 수 있다(D6). 전송 실패는 끝 상태이고 다시 보내려면 새 요청(D11).</li>
 *   <li>요청과 감사 기록은 DB 에 남는다. 파생본은 보관 기간이 지나면 지운다(D12).</li>
 * </ul>
 */
@Service
public class GatewayService {

    private final GatewayProperties props;
    private final FilterRegistry filters;
    private final TemplateLoader templates;
    private final AiClient aiClient;
    private final PreprocessProperties preprocessProps;

    private final Preprocessor preprocessor;
    private final GatewayRequestRepository requests;
    private final AuditEventRepository audit;

    public GatewayService(GatewayProperties props, FilterRegistry filters, TemplateLoader templates,
                          AiClient aiClient, PreprocessProperties preprocessProps,
                          GatewayRequestRepository requests, AuditEventRepository audit) {
        this.props = props;
        this.filters = filters;
        this.templates = templates;
        this.aiClient = aiClient;
        this.preprocessProps = preprocessProps;
        // 게이트웨이용 전처리: 반복 로그 줄이기와 크기 상한만 쓴다. 실험 장비 제거·정답 문구 삭제·민감값 주입은 실험 전용이라 비운다
        this.preprocessor = new Preprocessor(new PreprocessProperties(null, null, null, null, null, null, null,
                preprocessProps.keepFirst(), preprocessProps.keepLast(), preprocessProps.keepSlowest(),
                null, 0, 0, preprocessProps.maxInputTokens()));
        this.requests = requests;
        this.audit = audit;
    }

    public List<Role> roles(String user) {
        List<Role> roles = user == null ? null : props.users().get(user);
        if (roles == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "등록된 사용자가 아닙니다: " + user);
        }
        return roles;
    }

    public Map<String, List<Role>> users() {
        return props.users();
    }

    public List<String> endpoints() {
        return props.endpoints();
    }

    /** 요청 만들기. 원문은 이 메서드 밖으로 나가지 않는다. */
    // ponytail: 서비스 전체를 synchronized 로 잠근다. 요청이 몰리면 요청 단위 잠금(DB 행 잠금)으로 바꿀 것.
    public synchronized GatewayRequest create(String user, String log, String purpose, String endpointId) {
        require(user, Role.REQUESTER);
        String p = purpose == null || purpose.isBlank() ? ExperimentConstants.PURPOSE_ID : purpose;
        templates.get(p);
        String endpoint = endpointId == null || endpointId.isBlank() ? props.endpoints().get(0) : endpointId;
        if (!props.endpoints().contains(endpoint)) {
            throw new IllegalArgumentException("등록되지 않은 전송 대상입니다: " + endpoint + " (등록: " + props.endpoints() + ")");
        }
        if (log == null || log.isBlank()) {
            throw new IllegalArgumentException("로그가 비어 있습니다.");
        }
        List<Map<String, Object>> logs = LogFormat.parse(log);
        String id = nextId();
        List<Map<String, Object>> reduced = preprocessor.apply(logs).records();
        FilterResult r = filters.get(ExperimentConstants.ALLOWLIST).apply(reduced, id, p);
        int tokens = Preprocessor.estimateTokens(AiClient.buildPrompt(r.output(), ExperimentConstants.FIXED_QUESTION));
        if (tokens > preprocessProps.maxInputTokens()) {
            throw new IllegalArgumentException(String.format("파생본이 너무 큽니다: 약 %,d 토큰 (한도 %,d). 장애 구간을 더 좁혀 주세요.",
                    tokens, preprocessProps.maxInputTokens()));
        }
        Instant now = Instant.now();
        List<Warning> warnings = warnings(r.output());
        int high = warnings.stream().filter(w -> w.level() == WarningRules.Level.HIGH).mapToInt(Warning::count).sum();
        boolean approvalRequired = props.approvalMode() == ApprovalMode.ALWAYS || high > 0;
        GatewayRequest req = new GatewayRequest(id, user, p, endpoint, sha256(log), logs.size(), listVersion(p),
                r.output(), packageSha(r.output()), r.fieldsBefore(), r.fieldsAfter(), r.droppedFields(),
                warnings, approvalRequired, now, now.plus(Duration.ofDays(props.payloadRetentionDays())));
        requests.save(req);
        log(id, user, "CREATED", String.format("레코드 %d건 → 반복 줄여 %d건, 필드 %d → %d, 대상 %s, 목록 %s, 높음 경고 %d건 → %s",
                logs.size(), reduced.size(), r.fieldsBefore(), r.fieldsAfter(), endpoint, req.getListVersion(), high,
                approvalRequired ? "승인 필요" : "승인 없이 전송 가능(조건부)"));
        return req;
    }

    public List<GatewayRequest> list(String user, Status status) {
        roles(user);
        return status == null ? requests.findAllByOrderByIdDesc() : requests.findByStatusOrderByIdDesc(status);
    }

    public GatewayRequest get(String user, String id) {
        roles(user);
        return requests.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "요청이 없습니다: " + id));
    }

    public List<AuditEvent> audit(String user, String id) {
        get(user, id);
        return audit.findByRequestIdOrderByIdAsc(id);
    }

    /** 보관 기간이 지난 파생본을 지운다. 해시와 감사 기록은 남는다(D12). 매일 새벽 3시. */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeExpired() {
        purgeExpired(Instant.now());
    }

    public synchronized int purgeExpired(Instant now) {
        List<GatewayRequest> expired = requests.findByPayloadExpiresAtBeforeAndPayloadJsonIsNotNull(now);
        for (GatewayRequest r : expired) {
            r.purgePayload();
            requests.save(r);
            log(r.getId(), "gateway", "PAYLOAD_PURGED", "보관 기간(" + props.payloadRetentionDays() + "일)이 지나 파생본을 지움");
        }
        return expired.size();
    }

    private String nextId() {
        int n = (int) requests.count() + 1;
        while (requests.existsById(String.format("REQ-%04d", n))) {
            n++;
        }
        return String.format("REQ-%04d", n);
    }

    private void log(String id, String actor, String type, String detail) {
        audit.save(new AuditEvent(id, actor, type, detail));
    }

    public GatewayRequest decide(String user, String id, boolean approve, String comment, String seenPackageSha) {
        return decide(user, id, approve, comment, seenPackageSha, false);
    }

    /**
     * 승인 또는 반려. 승인이면 바로 전송한다(D7).
     *
     * @param seenPackageSha 승인자가 화면에서 본 파생본 해시. 지금 파생본과 다르면 승인하지 않는다.
     * @param falsePositive  높음 경고가 모두 오탐이라고 승인자가 확인했는지. 높음 경고가 있으면 이 확인과 사유가 있어야 승인된다
     */
    public synchronized GatewayRequest decide(String user, String id, boolean approve, String comment, String seenPackageSha,
                                              boolean falsePositive) {
        require(user, Role.APPROVER);
        GatewayRequest r = get(user, id);
        {
            if (user.equals(r.getRequester())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "자기 요청은 승인하거나 반려할 수 없습니다.");
            }
            if (r.getStatus() != Status.PREPARED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "승인 대기 상태가 아닙니다: " + r.getStatus());
            }
            if (!approve) {
                if (comment == null || comment.isBlank()) {
                    throw new IllegalArgumentException("반려 사유를 적어 주세요.");
                }
                r.reject(user, comment.trim());
                requests.save(r);
                log(id, user, "REJECTED", comment.trim() + " · 대기 " + waited(r));
                return r;
            }
            if (!r.getPackageSha256().equals(seenPackageSha)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "화면에서 본 파생본과 지금 파생본이 다릅니다. 다시 열어 확인해 주세요.");
            }
            int high = r.highWarnings();
            String note = null;
            if (high > 0) {
                if (!falsePositive || comment == null || comment.isBlank()) {
                    throw new IllegalArgumentException("높음 경고가 " + high + "건 있습니다. 진짜 민감값이면 반려하고, "
                            + "모두 오탐이면 '오탐 확인'에 표시하고 사유를 적어 승인하세요.");
                }
                note = "높음 경고 " + high + "건 오탐 확인: " + comment.trim();
            }
            String fp = fingerprint(r.getPackageSha256(), r.getListVersion(), r.getEndpointId());
            r.approve(user, fp, note);
            requests.save(r);
            log(id, user, "APPROVED", "지문 " + fp.substring(0, 12) + (note != null ? " · " + note : "") + " · 대기 " + waited(r));
            dispatch(r);
            requests.save(r);
            return r;
        }
    }

    /**
     * 조건부 승인: 높음 경고가 없는 요청을 요청자가 승인 없이 보낸다. 만들 때 승인 필요로 판정된 요청은 보낼 수 없고,
     * 보내기 직전에 파생본을 다시 검사해 높음 경고가 나오면 보내지 않는다(고장 나면 보내지 않는다).
     */
    public synchronized GatewayRequest sendWithoutApproval(String user, String id, String seenPackageSha) {
        require(user, Role.REQUESTER);
        GatewayRequest r = get(user, id);
        if (!user.equals(r.getRequester())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "승인 없이 보내기는 요청한 사람만 할 수 있습니다.");
        }
        if (r.getStatus() != Status.PREPARED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "승인 대기 상태가 아닙니다: " + r.getStatus());
        }
        if (r.isApprovalRequired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "승인이 필요한 요청입니다. 승인자의 승인을 받아야 보낼 수 있습니다.");
        }
        if (!r.getPackageSha256().equals(seenPackageSha)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "화면에서 본 파생본과 지금 파생본이 다릅니다. 다시 열어 확인해 주세요.");
        }
        int highNow = warnings(r.getPayload()).stream()
                .filter(w -> w.level() == WarningRules.Level.HIGH).mapToInt(Warning::count).sum();
        if (highNow > 0) {
            fail(r, "WARNING_RECHECK_FAILED", "보내기 직전 검사에서 높음 경고 " + highNow + "건이 나와 보내지 않았습니다.");
            requests.save(r);
            return r;
        }
        String fp = fingerprint(r.getPackageSha256(), r.getListVersion(), r.getEndpointId());
        r.skipApproval(fp, "승인 생략: 높음 경고 없음(조건부 승인)");
        requests.save(r);
        log(id, user, "APPROVAL_SKIPPED", "높음 경고 없음 · 조건부 승인 · 지문 " + fp.substring(0, 12));
        dispatch(r);
        requests.save(r);
        return r;
    }

    public ApprovalMode approvalMode() {
        return props.approvalMode();
    }

    /** 요청을 만든 뒤 결정까지 걸린 시간. 승인이 장애 대응을 얼마나 늦추는지 감사 기록으로 남긴다 */
    private static String waited(GatewayRequest r) {
        long s = Duration.between(r.getCreatedAt(), Instant.now()).toSeconds();
        return s >= 60 ? (s / 60) + "분 " + (s % 60) + "초" : s + "초";
    }

    /** 전송 직전 재확인: 파생본·목록 버전·전송 대상 중 하나라도 바뀌었으면 보내지 않는다. */
    private void dispatch(GatewayRequest r) {
        String now = fingerprint(packageSha(r.getPayload()), listVersion(r.getPurpose()), r.getEndpointId());
        if (!now.equals(r.getFingerprint())) {
            fail(r, "FINGERPRINT_MISMATCH", "승인 뒤 파생본·목록·전송 대상이 바뀌어 보내지 않았습니다.");
            return;
        }
        if (!props.endpoints().contains(r.getEndpointId()) || !r.getEndpointId().equals(aiClient.provider())) {
            fail(r, "ENDPOINT_REJECTED", "등록된 전송 대상이 아니어서 보내지 않았습니다: " + r.getEndpointId());
            return;
        }
        AiAnswer a = aiClient.ask(r.getPayload());
        if ("error".equals(a.mode())) {
            fail(r, "SEND_FAILED", a.text());
            return;
        }
        r.sent(a.text(), a.mode(), a.model());
        log(r.getId(), "gateway", "SENT", String.format("%s %s (%s), 프롬프트 %,d자", r.getEndpointId(), a.model(), a.mode(), a.promptChars()));
    }

    private void fail(GatewayRequest r, String type, String reason) {
        r.sendFailed(reason);
        log(r.getId(), "gateway", type, reason);
    }

    private void require(String user, Role role) {
        if (!roles(user).contains(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, user + " 에게 " + role + " 권한이 없습니다.");
        }
    }

    /** 목록 버전 = 활성 템플릿 내용의 해시. 목록이 바뀌면 버전도 바뀐다. */
    String listVersion(String purpose) {
        return sha256(LogFormat.toJson(templates.describe(purpose))).substring(0, 12);
    }

    static String packageSha(List<Map<String, Object>> payload) {
        return sha256(LogFormat.serialize(payload));
    }

    static String fingerprint(String packageSha, String listVersion, String endpointId) {
        return sha256(packageSha + "|" + listVersion + "|" + endpointId);
    }

    /** 경고 종류별 건수(규칙 순서). 위치까지 필요하면 {@link WarningRules#scan}. */
    static List<Warning> warnings(List<Map<String, Object>> payload) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        WarningRules.RULES.forEach(rule -> counts.put(rule.type(), 0));
        for (WarningRules.Finding f : WarningRules.scan(payload)) {
            counts.merge(f.type(), 1, Integer::sum);
        }
        List<Warning> out = new ArrayList<>();
        counts.forEach((type, n) -> {
            if (n > 0) {
                out.add(new Warning(type, n, WarningRules.levelOf(type)));
            }
        });
        return out;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
