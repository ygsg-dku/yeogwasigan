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
        AppProperties app = new AppProperties(null, null, null,
                new AppProperties.Ai("openai", null, 0, null, true, null, null), null, null);
        FilterRegistry filters = new FilterRegistry(List.of(TestSupport.passthrough(),
                new DenylistFilter(new PresidioClient(app), app), TestSupport.allowlistBodyImportantOnly()));
        GatewayProperties props = new GatewayProperties(Map.of(
                "ops-kim", List.of(Role.REQUESTER),
                "sec-park", List.of(Role.APPROVER),
                "lead-kang", List.of(Role.REQUESTER, Role.APPROVER)), List.of("openai"), 30, null);
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
        gateway.decide("sec-park", r.getId(), true, null, r.getPackageSha256());
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
        GatewayRequest after = gateway.decide("sec-park", r.getId(), true, null, seen);
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
}
