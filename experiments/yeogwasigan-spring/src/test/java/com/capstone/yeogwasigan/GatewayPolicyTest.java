package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.capstone.yeogwasigan.core.ai.AiClient;
import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.filter.DenylistFilter;
import com.capstone.yeogwasigan.core.filter.FilterRegistry;
import com.capstone.yeogwasigan.core.presidio.PresidioClient;
import com.capstone.yeogwasigan.gateway.AuditEvent;
import com.capstone.yeogwasigan.gateway.AuditEventRepository;
import com.capstone.yeogwasigan.gateway.GatewayProperties;
import com.capstone.yeogwasigan.gateway.GatewayProperties.ApprovalMode;
import com.capstone.yeogwasigan.gateway.GatewayProperties.Role;
import com.capstone.yeogwasigan.gateway.GatewayRequest;
import com.capstone.yeogwasigan.gateway.GatewayRequest.Status;
import com.capstone.yeogwasigan.gateway.GatewayRequestRepository;
import com.capstone.yeogwasigan.gateway.GatewayService;
import com.capstone.yeogwasigan.policy.PolicyEvaluator.Tier;
import com.capstone.yeogwasigan.preprocess.PreprocessProperties;

/**
 * 게이트웨이에 연결한 Policy Evaluator (docs/spec/16_POLICY_EVALUATOR.md).
 * 교수님 질문(8주차): "안전하다고 본 칸(메시지)에 카드번호가 들어가면 승인자 없이 나가지 않는가?"를 실제 요청으로 확인한다.
 * AI 는 mock 이라 외부 호출이 없다. 등록 대상은 "openai" 하나이고, 시험마다 그 등급을 T1 또는 T2 로 바꿔 본다.
 */
@DataJpaTest
class GatewayPolicyTest {

    /** 메시지(body) 칸은 담기 목록에 있어 그대로 나가는 칸이다. 그 안에 카드번호(Luhn 통과)가 섞였다. */
    private static final String CARD_IN_BODY = "{\"resource\":{\"service.name\":\"payment\"},\"scope\":{},\"logRecord\":"
            + "{\"timeUnixNano\":\"1790154142736724000\",\"severityText\":\"ERROR\",\"severityNumber\":17,"
            + "\"body\":\"charge failed for card 4111-1111-1111-1111: Invalid token.\"}}";

    /** 메시지 칸에 결제 API 비밀키가 섞였다. */
    private static final String SECRET_KEY = "sk_" + "live_51HqLyJ2eZvKYlo2C0ABCDEFGHIJ";
    private static final String SECRET_IN_BODY = "{\"resource\":{\"service.name\":\"payment\"},\"scope\":{},\"logRecord\":"
            + "{\"timeUnixNano\":\"1790154142736724000\",\"severityText\":\"ERROR\",\"severityNumber\":17,"
            + "\"body\":\"charge failed, retry with api_key=" + SECRET_KEY + "\"}}";

    private static final String CLEAN = "{\"resource\":{\"service.name\":\"payment\"},\"scope\":{},\"logRecord\":"
            + "{\"timeUnixNano\":\"1790154142736724000\",\"severityText\":\"WARN\",\"severityNumber\":13,"
            + "\"body\":\"Payment request failed. Invalid token.\"}}";

    @Autowired GatewayRequestRepository requests;
    @Autowired AuditEventRepository audit;

    private GatewayService gateway(Tier tier, ApprovalMode mode) {
        AppProperties app = new AppProperties(null, null, null,
                new AppProperties.Ai("openai", null, 0, null, true, null, null), null, null);
        FilterRegistry filters = new FilterRegistry(List.of(TestSupport.passthrough(),
                new DenylistFilter(new PresidioClient(app), app), TestSupport.allowlistBodyImportantOnly()));
        GatewayProperties props = new GatewayProperties(Map.of(
                "ops-kim", List.of(Role.REQUESTER),
                "sec-park", List.of(Role.APPROVER)), List.of("openai"), 30, null, mode, Map.of("openai", tier));
        PreprocessProperties pre = new PreprocessProperties(null, null, null, null, null, null, null,
                0, 0, 0, null, 0, 0, 0);
        return new GatewayService(props, filters, TestSupport.templates(), new AiClient(app), pre, requests, audit);
    }

    private static List<String> auditTypes(GatewayService g, GatewayRequest r) {
        return g.audit("sec-park", r.getId()).stream().map(AuditEvent::getType).toList();
    }

    private static String auditText(GatewayService g, GatewayRequest r) {
        return g.audit("sec-park", r.getId()).stream().map(AuditEvent::getDetail).toList().toString();
    }

    private static HttpStatus status(Runnable r) {
        return HttpStatus.valueOf(assertThrows(ResponseStatusException.class, r::run).getStatusCode().value());
    }

    @Test
    void 메시지_칸의_카드번호는_T1_에서_조건부_모드라도_승인자를_거친다() {
        GatewayService g = gateway(Tier.T1, ApprovalMode.CONDITIONAL);
        GatewayRequest r = g.create("ops-kim", CARD_IN_BODY, null, null);

        assertEquals(Status.PREPARED, r.getStatus());
        assertTrue(r.highWarnings() > 0, "카드번호가 높음 경고로 잡힌다: " + r.getWarnings());
        assertEquals("T1", r.getTier());
        assertEquals("T1_HIGH_WARNING", r.getPolicyReason());
        assertTrue(r.isApprovalRequired());
        assertEquals(HttpStatus.CONFLICT, status(() -> g.sendWithoutApproval("ops-kim", r.getId(), r.getPackageSha256())),
                "요청자 혼자서는 보낼 수 없다");
    }

    @Test
    void 메시지_칸의_카드번호는_T2_에서는_승인_없이_내부로_처리된다() {
        GatewayService g = gateway(Tier.T2, ApprovalMode.ALWAYS);
        GatewayRequest r = g.create("ops-kim", CARD_IN_BODY, null, null);

        assertTrue(r.highWarnings() > 0);
        assertEquals("T2", r.getTier());
        assertEquals("T2_READY", r.getPolicyReason());
        assertFalse(r.isApprovalRequired(), "T2 는 승인 모드·경고와 무관하게 승인자에게 가지 않는다");
        g.sendWithoutApproval("ops-kim", r.getId(), r.getPackageSha256());
        assertEquals(Status.SENT, requests.findById(r.getId()).orElseThrow().getStatus());
        assertEquals(List.of("CREATED", "APPROVAL_SKIPPED", "SENT"), auditTypes(g, r));
    }

    @Test
    void 메시지_칸의_비밀값은_T1_T2_모두_만들_때_거부되고_값이_어디에도_남지_않는다() {
        for (Tier tier : List.of(Tier.T1, Tier.T2)) {
            GatewayService g = gateway(tier, ApprovalMode.CONDITIONAL);
            GatewayRequest r = g.create("ops-kim", SECRET_IN_BODY, null, null);

            assertEquals(Status.DENIED, r.getStatus(), tier.name());
            assertEquals("RESIDUAL_SECRET_FOUND", r.getPolicyReason(), tier.name());
            assertTrue(r.isPayloadPurged(), "비밀값이 든 파생본은 저장하지 않는다");
            assertEquals(List.of("DENIED"), auditTypes(g, r));
            assertTrue(auditText(g, r).contains("STRIPE_KEY"), "어느 칸·규칙에 걸렸는지는 남는다");
            assertFalse(auditText(g, r).contains(SECRET_KEY), "감사 기록에 비밀값이 없다");
            assertFalse(r.getComment().contains(SECRET_KEY), "화면 사유에 비밀값이 없다");
        }
    }

    @Test
    void 거부된_요청은_승인하거나_보낼_수_없다() {
        GatewayService g = gateway(Tier.T1, ApprovalMode.CONDITIONAL);
        GatewayRequest denied = g.create("ops-kim", SECRET_IN_BODY, null, null);
        assertEquals(HttpStatus.CONFLICT, status(() -> g.decide("sec-park", denied.getId(), true, "x", denied.getPackageSha256(), true)));
        assertEquals(HttpStatus.CONFLICT, status(() -> g.sendWithoutApproval("ops-kim", denied.getId(), denied.getPackageSha256())));
        assertEquals(Status.DENIED, requests.findById(denied.getId()).orElseThrow().getStatus());
    }

    @Test
    void T0_대상은_내용이_깨끗해도_거부된다() {
        GatewayService g = gateway(Tier.T1, ApprovalMode.CONDITIONAL);
        GatewayRequest r = g.create("ops-kim", CLEAN, null, "personal-chatgpt");
        assertEquals(Status.DENIED, r.getStatus());
        assertEquals("T0", r.getTier());
        assertEquals("T0_DESTINATION", r.getPolicyReason());
        assertEquals(Tier.T0, g.tierOf("personal-chatgpt"));
    }

    @Test
    void T1_깨끗한_요청은_모드대로_승인_대기_또는_바로_전송() {
        GatewayRequest always = gateway(Tier.T1, ApprovalMode.ALWAYS).create("ops-kim", CLEAN, null, null);
        assertEquals("T1_APPROVAL_ALWAYS", always.getPolicyReason());
        assertTrue(always.isApprovalRequired());

        GatewayRequest conditional = gateway(Tier.T1, ApprovalMode.CONDITIONAL).create("ops-kim", CLEAN, null, null);
        assertEquals("T1_READY", conditional.getPolicyReason());
        assertFalse(conditional.isApprovalRequired());
    }

    @Test
    void 등급_설정은_등록된_대상에만_적을_수_있고_T0_는_적을_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> new GatewayProperties(Map.of(), List.of("openai"), 30, null,
                ApprovalMode.ALWAYS, Map.of("internal-llm", Tier.T2)));
        assertThrows(IllegalArgumentException.class, () -> gateway(Tier.T0, ApprovalMode.ALWAYS));
        assertEquals(Map.of("openai", Tier.T1),
                new GatewayProperties(Map.of(), List.of("openai"), 30, null, ApprovalMode.ALWAYS).registry(), "적지 않으면 T1");
    }
}
