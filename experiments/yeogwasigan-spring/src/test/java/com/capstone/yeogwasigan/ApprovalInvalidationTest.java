package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
import com.capstone.yeogwasigan.core.presidio.PresidioClient;
import com.capstone.yeogwasigan.core.template.TemplateLoader;
import com.capstone.yeogwasigan.gateway.AuditEvent;
import com.capstone.yeogwasigan.gateway.AuditEventRepository;
import com.capstone.yeogwasigan.gateway.GatewayProperties;
import com.capstone.yeogwasigan.gateway.GatewayProperties.ApprovalMode;
import com.capstone.yeogwasigan.gateway.GatewayProperties.Role;
import com.capstone.yeogwasigan.gateway.GatewayRequest;
import com.capstone.yeogwasigan.gateway.GatewayRequest.Status;
import com.capstone.yeogwasigan.gateway.GatewayRequestRepository;
import com.capstone.yeogwasigan.gateway.GatewayService;
import com.capstone.yeogwasigan.policy.PolicyEvaluator;
import com.capstone.yeogwasigan.policy.PolicyEvaluator.Tier;
import com.capstone.yeogwasigan.preprocess.PreprocessProperties;

/**
 * 승인 무효화: 승인 뒤 변경 시 전송 안 됨 (docs/spec/18_APPROVAL_INVALIDATION.md).
 *
 * <p>요청을 만들고 승인 대기 중에 무언가 바뀐 뒤 승인하면, 승인 지문이 맞지 않아 보내지 않아야 한다.
 * 바뀌는 것은 다섯 가지: 파생본, 담기 목록, 전송 대상, 대상 등급, 정책 규칙 버전.
 * "설정이 바뀐 서버"는 같은 DB 를 쓰는 새 GatewayService 로 흉내 낸다(재시작과 같다).
 * AI 는 mock 이라 외부 호출이 없다. 보내지 않았다는 것은 답이 비어 있고 SENT 기록이 없는 것으로 확인한다.
 */
@DataJpaTest
class ApprovalInvalidationTest {

    /** 높음 경고가 없는 깨끗한 로그. 승인자는 오탐 확인 없이 승인할 수 있다. */
    private static final String CLEAN = "{\"resource\":{\"service.name\":\"payment\"},\"scope\":{},\"logRecord\":"
            + "{\"timeUnixNano\":\"1790154142736724000\",\"severityText\":\"WARN\",\"severityNumber\":13,"
            + "\"body\":\"Payment request failed. Invalid token.\","
            + "\"attributes\":{\"http.response.status_code\":503}}}";

    @Autowired GatewayRequestRepository requests;
    @Autowired AuditEventRepository audit;
    @Autowired TestEntityManager em;

    private GatewayService gateway(ApprovalMode mode, Map<String, Tier> tiers, TemplateLoader templates) {
        AppProperties app = new AppProperties(null, null, null,
                new AppProperties.Ai("openai", null, 0, null, true, null, null), null, null);
        FilterRegistry filters = new FilterRegistry(List.of(TestSupport.passthrough(),
                new DenylistFilter(new PresidioClient(app), app), TestSupport.allowlistBodyImportantOnly()));
        GatewayProperties props = new GatewayProperties(Map.of(
                "ops-kim", List.of(Role.REQUESTER),
                "sec-park", List.of(Role.APPROVER)), List.copyOf(tiers.keySet()), 30, null, mode, tiers);
        PreprocessProperties pre = new PreprocessProperties(null, null, null, null, null, null, null,
                0, 0, 0, null, 0, 0, 0);
        return new GatewayService(props, filters, templates, new AiClient(app), pre, requests, audit);
    }

    /** 평소 서버: 대상 openai = T1, 항상 승인 모드 */
    private GatewayService t1() {
        return gateway(ApprovalMode.ALWAYS, Map.of("openai", Tier.T1), TestSupport.templates());
    }

    /** 담기 목록에서 응답 코드 칸 한 줄을 뺀 템플릿. 목록이 바뀌면 목록 버전(내용 해시)도 바뀐다. */
    private static TemplateLoader listWithoutStatusCode() {
        try (var in = ApprovalInvalidationTest.class.getResourceAsStream("/templates/incident_analysis.yaml")) {
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String changed = yaml.lines().filter(l -> !l.trim().startsWith("- logRecord.attributes.http.response.status_code"))
                    .reduce("", (a, b) -> a + b + "\n");
            assertNotEquals(yaml.lines().count(), changed.lines().count(), "목록에서 한 줄이 빠져야 한다");
            return TemplateLoader.of(List.of(TemplateLoader.parse(
                    new ByteArrayInputStream(changed.getBytes(StandardCharsets.UTF_8)))));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** DB 에서 요청 한 칸을 직접 바꾼다(저장소를 건드린 경우, 또는 예전 규칙으로 만든 요청). */
    private void tamper(GatewayRequest r, String column, String value) {
        em.flush();
        em.getEntityManager().createNativeQuery("update gateway_request set " + column + " = ? where id = ?")
                .setParameter(1, value).setParameter(2, r.getId()).executeUpdate();
        em.clear();
    }

    private static List<String> auditTypes(GatewayService g, GatewayRequest r) {
        return g.audit("sec-park", r.getId()).stream().map(AuditEvent::getType).toList();
    }

    private static String lastAudit(GatewayService g, GatewayRequest r) {
        List<AuditEvent> events = g.audit("sec-park", r.getId());
        return events.get(events.size() - 1).getDetail();
    }

    /** 승인했지만 보내지 않았는지 확인한다: 끝 상태 SEND_FAILED, 답 없음, 마지막 기록 FINGERPRINT_MISMATCH. */
    private void assertNotSent(GatewayService g, GatewayRequest after, String expectedChange) {
        assertEquals(Status.SEND_FAILED, after.getStatus());
        assertNull(after.getAnswer(), "AI 에 보내지 않았으니 답이 없다");
        assertEquals(List.of("CREATED", "APPROVED", "FINGERPRINT_MISMATCH"), auditTypes(g, after));
        assertTrue(lastAudit(g, after).contains(expectedChange), "무엇이 바뀌었는지 기록한다: " + lastAudit(g, after));
    }

    @Test
    void 아무것도_바뀌지_않으면_승인_후_보낸다() {
        GatewayService g = t1();
        GatewayRequest r = g.create("ops-kim", CLEAN, null, null);
        assertEquals(Status.PREPARED, r.getStatus());
        assertEquals("T1", r.getTier());
        assertEquals(PolicyEvaluator.POLICY_VERSION, r.getPolicyVersion(), "만들 때 쓴 규칙 버전을 남긴다");

        GatewayRequest after = g.decide("sec-park", r.getId(), true, null, r.getPackageSha256());
        assertEquals(Status.SENT, after.getStatus());
        assertEquals(List.of("CREATED", "APPROVED", "SENT"), auditTypes(g, after));
    }

    @Test
    void 승인_뒤_파생본이_바뀌면_보내지_않는다() {
        GatewayService g = t1();
        GatewayRequest r = g.create("ops-kim", CLEAN, null, null);
        String seen = r.getPackageSha256();
        tamper(r, "payload_json", "[{\"tampered\":\"user@example.com\"}]");

        assertNotSent(g, g.decide("sec-park", r.getId(), true, null, seen), "파생본");
    }

    @Test
    void 승인_뒤_담기_목록이_바뀌면_보내지_않는다() {
        GatewayRequest r = t1().create("ops-kim", CLEAN, null, null);
        // 승인 대기 중에 운영자가 담기 목록을 고치고 서버를 다시 띄웠다
        GatewayService changed = gateway(ApprovalMode.ALWAYS, Map.of("openai", Tier.T1), listWithoutStatusCode());

        assertNotSent(changed, changed.decide("sec-park", r.getId(), true, null, r.getPackageSha256()), "목록");
    }

    @Test
    void 승인_뒤_전송_대상이_바뀌면_보내지_않는다() {
        GatewayService g = t1();
        GatewayRequest r = g.create("ops-kim", CLEAN, null, null);
        tamper(r, "endpoint_id", "personal-chatgpt");

        assertNotSent(g, g.decide("sec-park", r.getId(), true, null, r.getPackageSha256()), "대상 등급 T1→T0");
    }

    @Test
    void 승인_뒤_대상_등급이_T1에서_T2로_바뀌어도_보내지_않는다() {
        GatewayRequest r = t1().create("ops-kim", CLEAN, null, null);
        GatewayService changed = gateway(ApprovalMode.ALWAYS, Map.of("openai", Tier.T2), TestSupport.templates());

        assertNotSent(changed, changed.decide("sec-park", r.getId(), true, null, r.getPackageSha256()), "대상 등급 T1→T2");
    }

    @Test
    void 승인_뒤_대상이_등록에서_빠지면_보내지_않는다() {
        GatewayRequest r = t1().create("ops-kim", CLEAN, null, null);
        // openai 를 등록부에서 빼고 다른 대상만 남겼다 → openai 는 이제 T0
        GatewayService changed = gateway(ApprovalMode.ALWAYS, Map.of("anthropic", Tier.T1), TestSupport.templates());

        assertNotSent(changed, changed.decide("sec-park", r.getId(), true, null, r.getPackageSha256()), "대상 등급 T1→T0");
    }

    @Test
    void 승인_뒤_정책_규칙_버전이_바뀌면_보내지_않는다() {
        GatewayService g = t1();
        GatewayRequest r = g.create("ops-kim", CLEAN, null, null);
        // 예전 규칙(policy-v0)으로 만들어 승인 대기 중이던 요청을 새 규칙 서버에서 승인하는 경우
        tamper(r, "policy_version", "policy-v0");

        assertNotSent(g, g.decide("sec-park", r.getId(), true, null, r.getPackageSha256()),
                "정책 규칙 policy-v0→" + PolicyEvaluator.POLICY_VERSION);
    }

    @Test
    void 승인_없이_보내는_길도_만든_뒤_바뀌면_보내지_않는다() {
        Map<String, Tier> tiers = Map.of("openai", Tier.T1);
        GatewayRequest r = gateway(ApprovalMode.CONDITIONAL, tiers, TestSupport.templates()).create("ops-kim", CLEAN, null, null);
        assertFalse(r.isApprovalRequired(), "경고 없음 + 조건부 모드라 승인 없이 보낼 수 있는 요청");
        GatewayService changed = gateway(ApprovalMode.CONDITIONAL, tiers, listWithoutStatusCode());

        GatewayRequest after = changed.sendWithoutApproval("ops-kim", r.getId(), r.getPackageSha256());
        assertEquals(Status.SEND_FAILED, after.getStatus());
        assertNull(after.getAnswer());
        assertEquals(List.of("CREATED", "APPROVAL_SKIPPED", "FINGERPRINT_MISMATCH"), auditTypes(changed, after));
    }

    @Test
    void 무효가_된_승인은_다시_쓸_수_없다() {
        GatewayRequest r = t1().create("ops-kim", CLEAN, null, null);
        GatewayService changed = gateway(ApprovalMode.ALWAYS, Map.of("openai", Tier.T2), TestSupport.templates());
        changed.decide("sec-park", r.getId(), true, null, r.getPackageSha256());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> changed.decide("sec-park", r.getId(), true, null, r.getPackageSha256()));
        assertEquals(HttpStatus.CONFLICT.value(), e.getStatusCode().value(), "끝난 요청은 다시 승인할 수 없다. 새 요청을 만든다");
    }
}
