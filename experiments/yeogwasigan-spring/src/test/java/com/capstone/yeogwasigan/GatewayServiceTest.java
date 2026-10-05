package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.capstone.yeogwasigan.core.ai.AiClient;
import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.filter.DenylistFilter;
import com.capstone.yeogwasigan.core.filter.FilterRegistry;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.presidio.PresidioClient;
import com.capstone.yeogwasigan.gateway.GatewayProperties;
import com.capstone.yeogwasigan.gateway.GatewayProperties.Role;
import com.capstone.yeogwasigan.gateway.GatewayRequest;
import com.capstone.yeogwasigan.gateway.GatewayRequest.Status;
import com.capstone.yeogwasigan.gateway.GatewayService;
import com.capstone.yeogwasigan.preprocess.PreprocessProperties;

/** 설계안 v0.2 의 게이트웨이 보안 시험을 서비스 단위로 확인한다. AI 는 mock 이라 외부 호출이 없다. */
class GatewayServiceTest {

    private static final String LOG;

    static {
        try {
            LOG = Files.readString(Path.of("scenarios", TestSupport.SAMPLE, "raw.jsonl"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private final GatewayService gateway = gateway();

    private static GatewayService gateway() {
        AppProperties app = new AppProperties(null, null, null,
                new AppProperties.Ai("openai", null, 0, null, true, null, null), null, null);
        FilterRegistry filters = new FilterRegistry(List.of(TestSupport.passthrough(),
                new DenylistFilter(new PresidioClient(app), app), TestSupport.allowlistBodyImportantOnly()));
        GatewayProperties props = new GatewayProperties(Map.of(
                "ops-kim", List.of(Role.REQUESTER),
                "sec-park", List.of(Role.APPROVER),
                "lead-kang", List.of(Role.REQUESTER, Role.APPROVER)), List.of("openai"));
        PreprocessProperties pre = new PreprocessProperties(null, null, null, null, null, null, null,
                0, 0, 0, null, 0, 0, 0);
        return new GatewayService(props, filters, TestSupport.templates(), new AiClient(app), pre);
    }

    private static HttpStatus status(Runnable r) {
        return HttpStatus.valueOf(assertThrows(ResponseStatusException.class, r::run).getStatusCode().value());
    }

    private static List<String> auditTypes(GatewayRequest r) {
        return r.getAudit().stream().map(GatewayRequest.AuditEvent::type).toList();
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
        assertEquals(Status.SENT, r.getStatus());
        assertEquals("mock", r.getAnswerMode());
        assertEquals(List.of("CREATED", "APPROVED", "SENT"), auditTypes(r));
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
    void 승인_뒤_파생본이_바뀌면_보내지_않는다() {
        GatewayRequest r = gateway.create("ops-kim", LOG, null, null);
        String seen = r.getPackageSha256();
        r.getPayload().get(0).put("tampered", "user@example.com");   // 메모리에서 파생본을 바꿔치기한 상황
        gateway.decide("sec-park", r.getId(), true, null, seen);
        assertEquals(Status.SEND_FAILED, r.getStatus());
        assertEquals(List.of("CREATED", "APPROVED", "FINGERPRINT_MISMATCH"), auditTypes(r));
        assertEquals(null, r.getAnswer());
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
        assertEquals(Status.REJECTED, r.getStatus());
        assertEquals(HttpStatus.CONFLICT, status(() -> gateway.decide("sec-park", r.getId(), true, null, r.getPackageSha256())));
    }
}
