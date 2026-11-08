package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.capstone.yeogwasigan.core.ai.AiClient;
import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.filter.DenylistFilter;
import com.capstone.yeogwasigan.core.filter.FilterRegistry;
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

/** 전송 직전에 막히는 경로가 감사 기록에 그 이유 그대로 남는지 확인한다. AI 는 mock. */
@DataJpaTest
class GatewayFailureAuditTest {

    private static final String LOG;

    static {
        try {
            LOG = Files.readString(Path.of("scenarios", TestSupport.SAMPLE, "raw.jsonl"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 본문에 카드번호·이메일이 섞인 WARN 기록 하나 → 높음 경고 2건 */
    private static final String HIGH_LOG = "{\"resource\":{\"service.name\":\"payment\"},\"scope\":{},\"logRecord\":"
            + "{\"timeUnixNano\":\"1790154142736724000\",\"severityText\":\"WARN\",\"severityNumber\":13,"
            + "\"body\":\"charge failed card 4111-1111-1111-1111 user kim@bank.com\"}}";
    /** 민감값이 없는 기록 하나 → 높음 경고 0건 */
    private static final String CLEAN_LOG = "{\"resource\":{\"service.name\":\"payment\"},\"scope\":{},\"logRecord\":"
            + "{\"timeUnixNano\":\"1790154142736724000\",\"severityText\":\"WARN\",\"severityNumber\":13,"
            + "\"body\":\"Payment request failed. Invalid token.\"}}";

    @Autowired GatewayRequestRepository requests;
    @Autowired AuditEventRepository audit;
    @Autowired TestEntityManager em;

    private GatewayService newService(GatewayProperties.ApprovalMode mode, List<String> endpoints) {
        AppProperties app = new AppProperties(null, null, null,
                new AppProperties.Ai("openai", null, 0, null, true, null, null, null), null, null);
        FilterRegistry filters = new FilterRegistry(List.of(TestSupport.passthrough(),
                new DenylistFilter(new PresidioClient(app), app), TestSupport.allowlistBodyImportantOnly()));
        GatewayProperties props = new GatewayProperties(Map.of(
                "ops-kim", List.of(Role.REQUESTER),
                "sec-park", List.of(Role.APPROVER),
                "lead-kang", List.of(Role.REQUESTER, Role.APPROVER)), endpoints, 30, null, mode);
        PreprocessProperties pre = new PreprocessProperties(null, null, null, null, null, null, null,
                0, 0, 0, null, 0, 0, 0);
        return new GatewayService(props, filters, TestSupport.templates(), new AiClient(app), pre, requests, audit);
    }

    private static List<String> types(GatewayService gateway, String user, GatewayRequest r) {
        return gateway.audit(user, r.getId()).stream().map(AuditEvent::getType).toList();
    }

    @Test
    void 보내기_직전_검사에서_높음_경고가_나오면_보내지_않고_이유가_남는다() {
        GatewayService conditional = newService(GatewayProperties.ApprovalMode.CONDITIONAL, List.of("openai"));
        GatewayRequest clean = conditional.create("ops-kim", CLEAN_LOG, null, null);
        GatewayRequest risky = conditional.create("ops-kim", HIGH_LOG, null, null);
        assertFalse(clean.isApprovalRequired());
        String seen = clean.getPackageSha256();
        em.flush();
        // DB 에서 파생본만 위험한 값이 든 것으로 바꿔치기한다. 해시 칸은 그대로라 화면 확인은 통과한다.
        em.getEntityManager().createNativeQuery(
                "update gateway_request set payload_json = (select payload_json from gateway_request where id = ?) where id = ?")
                .setParameter(1, risky.getId()).setParameter(2, clean.getId()).executeUpdate();
        em.clear();

        GatewayRequest after = conditional.sendWithoutApproval("ops-kim", clean.getId(), seen);
        assertEquals(Status.SEND_FAILED, after.getStatus());
        assertNull(after.getAnswer());
        assertEquals(List.of("CREATED", "WARNING_RECHECK_FAILED"), types(conditional, "ops-kim", clean));
    }

    @Test
    void 승인_뒤_전송_대상이_등록_목록에서_빠지면_보내지_않고_이유가_남는다() {
        GatewayService creator = newService(GatewayProperties.ApprovalMode.ALWAYS, List.of("openai"));
        GatewayRequest r = creator.create("ops-kim", LOG, null, null);
        // 서버 설정이 바뀌어 등록된 전송 대상이 달라진 상황: 같은 DB, 다른 설정으로 다시 띄운다.
        GatewayService changed = newService(GatewayProperties.ApprovalMode.ALWAYS, List.of("other-ai"));

        GatewayRequest after = changed.decide("sec-park", r.getId(), true, "샘플의 예시 값", r.getPackageSha256(), true);
        assertEquals(Status.SEND_FAILED, after.getStatus());
        assertNull(after.getAnswer());
        assertEquals(List.of("CREATED", "APPROVED", "ENDPOINT_REJECTED"), types(changed, "sec-park", r));
    }
}