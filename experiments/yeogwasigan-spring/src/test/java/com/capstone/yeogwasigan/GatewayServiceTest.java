package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.capstone.yeogwasigan.core.ai.AiClient;
import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.filter.DenylistFilter;
import com.capstone.yeogwasigan.core.filter.FilterRegistry;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.presidio.PresidioClient;
import com.capstone.yeogwasigan.gateway.AuditEvent;
import com.capstone.yeogwasigan.gateway.AuditEventRepository;
import com.capstone.yeogwasigan.gateway.GatewayProperties;
import com.capstone.yeogwasigan.gateway.GatewayProperties.Role;
import com.capstone.yeogwasigan.gateway.GatewayRequest;
import com.capstone.yeogwasigan.gateway.GatewayRequest.Status;
import com.capstone.yeogwasigan.gateway.GatewayRequestRepository;
import com.capstone.yeogwasigan.gateway.GatewayService;
import com.capstone.yeogwasigan.preprocess.PreprocessProperties;

/** 설계안 v0.2 의 게이트웨이 보안 시험. 내장 H2 DB 로 저장까지 확인한다. AI 는 mock 이라 외부 호출이 없다. */
@DataJpaTest
class GatewayServiceTest {

    private static final String LOG;

    static {
        try {
            LOG = Files.readString(Path.of("scenarios", TestSupport.SAMPLE, "raw.jsonl"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Autowired GatewayRequestRepository requests;
    @Autowired AuditEventRepository audit;
    @Autowired TestEntityManager em;

    private GatewayService gateway;

    @BeforeEach
    void setUp() {
        gateway = newService();
    }

    private GatewayService newService() {
        return newService(GatewayProperties.ApprovalMode.ALWAYS);
    }

    private GatewayService newService(GatewayProperties.ApprovalMode mode) {
        AppProperties app = new AppProperties(null, null, null,
                new AppProperties.Ai("openai", null, 0, null, true, null, null), null, null);
        FilterRegistry filters = new FilterRegistry(List.of(TestSupport.passthrough(),
                new DenylistFilter(new PresidioClient(app), app), TestSupport.allowlistBodyImportantOnly()));
        GatewayProperties props = new GatewayProperties(Map.of(
                "ops-kim", List.of(Role.REQUESTER),
                "sec-park", List.of(Role.APPROVER),
                "lead-kang", List.of(Role.REQUESTER, Role.APPROVER)), List.of("openai"), 30, null, mode);
        PreprocessProperties pre = new PreprocessProperties(null, null, null, null, null, null, null,
                0, 0, 0, null, 0, 0, 0);
        return new GatewayService(props, filters, TestSupport.templates(), new AiClient(app), pre, requests, audit);
    }

    private static HttpStatus status(Runnable r) {
        return HttpStatus.valueOf(assertThrows(ResponseStatusException.class, r::run).getStatusCode().value());
    }

    private List<String> auditTypes(GatewayRequest r) {
        return gateway.audit("sec-park", r.getId()).stream().map(AuditEvent::getType).toList();
    }

    @Test
    void 요청을_만들면_목록_칸만_남은_파생본과_원문_해시만_남는다() {
        GatewayRequest r = gateway.create("ops-kim", LOG, null, null);
        assertEquals(Status.PREPARED, r.getStatus());
        assertTrue(r.getFieldsAfter() <= 10, "목록 10칸 이하");
        assertTrue(r.getFieldsBefore() > r.getFieldsAfter());
        assertEquals(64, r.getInputSha256().length());
        assertFalse(LogFormat.serialize(r.getPayload()).contains("host.name"), "목록 밖 칸은 나가지 않는다");
        assertEquals(List.of("CREATED"), auditTypes(r));
    }

    @Test
    void 다른_사람이_승인하면_등록된_대상으로_전송되고_감사_기록이_남는다() {
        GatewayRequest r = gateway.create("ops-kim", LOG, null, null);
        // 샘플 본문에 높음 경고가 있어 오탐 확인과 사유가 필요하다(승인 절차 v0.1)
        gateway.decide("sec-park", r.getId(), true, "샘플의 예시 값", r.getPackageSha256(), true);
        GatewayRequest saved = requests.findById(r.getId()).orElseThrow();
        assertEquals(Status.SENT, saved.getStatus());
        assertEquals("mock", saved.getAnswerMode());
        assertEquals(List.of("CREATED", "APPROVED", "SENT"), auditTypes(r));
    }

    @Test
    void 재시작해도_요청과_감사_기록이_남는다() {
        GatewayRequest r = gateway.create("ops-kim", LOG, null, null);
        em.flush();
        em.clear();
        GatewayService restarted = newService();
        assertEquals(Status.PREPARED, restarted.get("sec-park", r.getId()).getStatus());
        assertEquals(List.of("CREATED"), restarted.audit("sec-park", r.getId()).stream().map(AuditEvent::getType).toList());
        assertEquals("REQ-0002", restarted.create("ops-kim", LOG, null, null).getId(), "번호가 이어진다");
    }

    @Test
    void 자기_요청은_승인할_수_없다() {
        GatewayRequest r = gateway.create("lead-kang", LOG, null, null);
        assertEquals(HttpStatus.FORBIDDEN, status(() -> gateway.decide("lead-kang", r.getId(), true, null, r.getPackageSha256())));
        assertEquals(Status.PREPARED, r.getStatus());
    }

    @Test
    void 승인자_역할이_없으면_승인할_수_없고_등록되지_않은_사용자는_401() {
        GatewayRequest r = gateway.create("ops-kim", LOG, null, null);
        assertEquals(HttpStatus.FORBIDDEN, status(() -> gateway.decide("ops-kim", r.getId(), true, null, r.getPackageSha256())));
        assertEquals(HttpStatus.UNAUTHORIZED, status(() -> gateway.create("nobody", LOG, null, null)));
    }

    @Test
    void 화면에서_본_파생본과_다르면_승인하지_않는다() {
        GatewayRequest r = gateway.create("ops-kim", LOG, null, null);
        assertEquals(HttpStatus.CONFLICT, status(() -> gateway.decide("sec-park", r.getId(), true, null, "0".repeat(64))));
        assertEquals(Status.PREPARED, r.getStatus());
    }

    @Test
    void DB에서_파생본을_바꿔치기하면_보내지_않는다() {
        GatewayRequest r = gateway.create("ops-kim", LOG, null, null);
        String seen = r.getPackageSha256();
        em.flush();
        em.getEntityManager().createNativeQuery("update gateway_request set payload_json = ? where id = ?")
                .setParameter(1, "[{\"tampered\":\"user@example.com\"}]").setParameter(2, r.getId()).executeUpdate();
        em.clear();
        GatewayRequest after = gateway.decide("sec-park", r.getId(), true, "샘플의 예시 값", seen, true);
        assertEquals(Status.SEND_FAILED, after.getStatus());
        assertEquals(List.of("CREATED", "APPROVED", "FINGERPRINT_MISMATCH"), auditTypes(r));
        assertNull(after.getAnswer());
    }

    @Test
    void 보관_기간이_지나면_파생본만_지우고_기록은_남긴다() {
        GatewayRequest r = gateway.create("ops-kim", LOG, null, null);
        assertEquals(0, gateway.purgeExpired(Instant.now()));
        assertEquals(1, gateway.purgeExpired(Instant.now().plus(Duration.ofDays(31))));
        GatewayRequest saved = requests.findById(r.getId()).orElseThrow();
        assertTrue(saved.isPayloadPurged());
        assertEquals(64, saved.getPackageSha256().length(), "해시는 남는다");
        assertEquals(List.of("CREATED", "PAYLOAD_PURGED"), auditTypes(r));
    }

    @Test
    void 같은_로그가_반복되면_줄여서_보낸다() {
        String line = LOG.lines().filter(l -> l.contains("\"INFO\"") || l.contains("\"info\"")).findFirst().orElseThrow();
        String repeated = String.join("\n", java.util.Collections.nCopies(30, line));
        GatewayRequest r = gateway.create("ops-kim", repeated, null, null);
        assertEquals(30, r.getInputRecords());
        assertTrue(r.getPayload().size() < 30, "반복 기록은 처음 몇 건과 마지막 건만 남는다");
    }

    @Test
    void 등록되지_않은_전송_대상으로는_요청을_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> gateway.create("ops-kim", LOG, null, "https://evil.example"));
    }

    @Test
    void 반려는_사유가_필요하고_끝난_요청은_다시_결정할_수_없다() {
        GatewayRequest r = gateway.create("ops-kim", LOG, null, null);
        assertThrows(IllegalArgumentException.class, () -> gateway.decide("sec-park", r.getId(), false, " ", null));
        gateway.decide("sec-park", r.getId(), false, "본문에 사용자 ID가 보임", null);
        assertEquals(Status.REJECTED, requests.findById(r.getId()).orElseThrow().getStatus());
        assertEquals(HttpStatus.CONFLICT, status(() -> gateway.decide("sec-park", r.getId(), true, null, r.getPackageSha256())));
    }

    // ---------------------------------------------------------------- 승인 절차 v0.1 (docs/spec/14_APPROVAL_AND_WARNING.md)

    /** 본문에 카드번호·이메일이 섞인 WARN 기록 하나 → 높음 경고 2건 */
    private static final String HIGH_LOG = "{\"resource\":{\"service.name\":\"payment\"},\"scope\":{},\"logRecord\":"
            + "{\"timeUnixNano\":\"1790154142736724000\",\"severityText\":\"WARN\",\"severityNumber\":13,"
            + "\"body\":\"charge failed card 4111-1111-1111-1111 user kim@bank.com\"}}";
    /** 민감값이 없는 기록 하나 → 높음 경고 0건 */
    private static final String CLEAN_LOG = "{\"resource\":{\"service.name\":\"payment\"},\"scope\":{},\"logRecord\":"
            + "{\"timeUnixNano\":\"1790154142736724000\",\"severityText\":\"WARN\",\"severityNumber\":13,"
            + "\"body\":\"Payment request failed. Invalid token.\"}}";

    @Test
    void 높음_경고가_있으면_오탐_확인과_사유가_있어야_승인된다() {
        GatewayRequest r = gateway.create("ops-kim", HIGH_LOG, null, null);
        assertEquals(2, r.highWarnings());
        assertTrue(r.isApprovalRequired());
        String sha = r.getPackageSha256();
        assertThrows(IllegalArgumentException.class, () -> gateway.decide("sec-park", r.getId(), true, null, sha, false));
        assertThrows(IllegalArgumentException.class, () -> gateway.decide("sec-park", r.getId(), true, " ", sha, true));
        assertEquals(Status.PREPARED, requests.findById(r.getId()).orElseThrow().getStatus(), "확인 없이는 아무것도 바뀌지 않는다");

        gateway.decide("sec-park", r.getId(), true, "시험용 카드번호와 예시 주소", sha, true);
        assertEquals(Status.SENT, requests.findById(r.getId()).orElseThrow().getStatus());
        AuditEvent approved = gateway.audit("sec-park", r.getId()).get(1);
        assertEquals("APPROVED", approved.getType());
        assertTrue(approved.getDetail().contains("높음 경고 2건 오탐 확인: 시험용 카드번호와 예시 주소"), approved.getDetail());
        assertTrue(approved.getDetail().contains("대기"), "결정까지 걸린 시간을 남긴다");
    }

    @Test
    void 진짜_민감값이면_반려하고_사유와_대기시간이_남는다() {
        GatewayRequest r = gateway.create("ops-kim", HIGH_LOG, null, null);
        gateway.decide("sec-park", r.getId(), false, "본문에 실제 카드번호", null, false);
        AuditEvent rejected = gateway.audit("sec-park", r.getId()).get(1);
        assertEquals("REJECTED", rejected.getType());
        assertTrue(rejected.getDetail().startsWith("본문에 실제 카드번호 · 대기 "), rejected.getDetail());
    }

    @Test
    void 항상_승인_모드에서는_높음_경고가_없어도_승인_없이_보낼_수_없다() {
        GatewayRequest r = gateway.create("ops-kim", CLEAN_LOG, null, null);
        assertEquals(0, r.highWarnings());
        assertTrue(r.isApprovalRequired());
        assertEquals(HttpStatus.CONFLICT, status(() -> gateway.sendWithoutApproval("ops-kim", r.getId(), r.getPackageSha256())));
    }

    @Test
    void 조건부_모드에서_높음_경고가_없으면_요청자가_승인_없이_보낸다() {
        GatewayService conditional = newService(GatewayProperties.ApprovalMode.CONDITIONAL);
        GatewayRequest r = conditional.create("ops-kim", CLEAN_LOG, null, null);
        assertFalse(r.isApprovalRequired());
        conditional.sendWithoutApproval("ops-kim", r.getId(), r.getPackageSha256());
        GatewayRequest saved = requests.findById(r.getId()).orElseThrow();
        assertEquals(Status.SENT, saved.getStatus());
        assertNull(saved.getApprover(), "승인자 없이 나갔음을 그대로 남긴다");
        assertEquals(List.of("CREATED", "APPROVAL_SKIPPED", "SENT"),
                conditional.audit("ops-kim", r.getId()).stream().map(AuditEvent::getType).toList());
    }

    @Test
    void 조건부_모드라도_높음_경고가_있으면_승인자를_거쳐야_한다() {
        GatewayService conditional = newService(GatewayProperties.ApprovalMode.CONDITIONAL);
        GatewayRequest r = conditional.create("ops-kim", HIGH_LOG, null, null);
        assertTrue(r.isApprovalRequired());
        assertEquals(HttpStatus.CONFLICT, status(() -> conditional.sendWithoutApproval("ops-kim", r.getId(), r.getPackageSha256())));
    }

    @Test
    void 승인_없이_보내기는_요청한_사람만_할_수_있다() {
        GatewayService conditional = newService(GatewayProperties.ApprovalMode.CONDITIONAL);
        GatewayRequest r = conditional.create("ops-kim", CLEAN_LOG, null, null);
        assertEquals(HttpStatus.FORBIDDEN, status(() -> conditional.sendWithoutApproval("lead-kang", r.getId(), r.getPackageSha256())));
        assertEquals(HttpStatus.FORBIDDEN, status(() -> conditional.sendWithoutApproval("sec-park", r.getId(), r.getPackageSha256())),
                "요청자 역할이 없으면 403");
    }
}
