package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

/**
 * 요청 상태 저장과 감사 기록 저장이 한 묶음으로 확정되는지 확인한다. AI 는 mock.
 *
 * 시험 전체가 하나의 트랜잭션으로 감싸이면 "취소됐는지"가 보이지 않으므로, 이 클래스는 트랜잭션 밖에서 돌린다
 * (NOT_SUPPORTED). 대신 시험이 끝나면 만든 기록을 직접 지운다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GatewayAtomicSaveTest {

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
    @Autowired PlatformTransactionManager txManager;

        @Autowired TestEntityManager em;

    /** 감사 저장소에는 삭제 메서드가 없다(기록은 지우지 않는 설계). 시험이 남긴 기록만 직접 지운다. */
    @AfterEach
    void cleanUp() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.getEntityManager().createQuery("delete from AuditEvent").executeUpdate();
            em.getEntityManager().createQuery("delete from GatewayRequest").executeUpdate();
        });
    }

    /** 묶음 저장을 켠 서비스(운영과 같은 생성자). 감사 저장소만 바꿔 끼울 수 있다. */
    private GatewayService service(AuditEventRepository auditRepo) {
        AppProperties app = new AppProperties(null, null, null,
                new AppProperties.Ai("openai", null, 0, null, true, null, null, null), null, null);
        FilterRegistry filters = new FilterRegistry(List.of(TestSupport.passthrough(),
                new DenylistFilter(new PresidioClient(app), app), TestSupport.allowlistBodyImportantOnly()));
        GatewayProperties props = new GatewayProperties(Map.of(
                "ops-kim", List.of(Role.REQUESTER),
                "sec-park", List.of(Role.APPROVER)), List.of("openai"), 30, null,
                GatewayProperties.ApprovalMode.ALWAYS);
        PreprocessProperties pre = new PreprocessProperties(null, null, null, null, null, null, null,
                0, 0, 0, null, 0, 0, 0);
        return new GatewayService(props, filters, TestSupport.templates(), new AiClient(app), pre, requests,
                auditRepo, new TransactionTemplate(txManager));
    }

    private List<String> typesOf(String id) {
        return audit.findByRequestIdOrderByIdAsc(id).stream().map(AuditEvent::getType).toList();
    }

    @Test
    void 감사_기록_저장이_실패하면_승인도_함께_취소된다() {
        GatewayRequest r = service(audit).create("ops-kim", LOG, null, null);
        Status before = r.getStatus();

        AuditEventRepository broken = mock(AuditEventRepository.class);
        when(broken.save(any(AuditEvent.class))).thenThrow(new IllegalStateException("감사 저장소 고장"));
        GatewayService failing = service(broken);

        assertThrows(IllegalStateException.class,
                () -> failing.decide("sec-park", r.getId(), true, "샘플의 예시 값", r.getPackageSha256(), true));

        // 묶음이면: 승인 저장도 함께 취소돼 상태가 그대로이고, 감사 기록은 만들 때의 한 건뿐이다
        assertEquals(before, requests.findById(r.getId()).orElseThrow().getStatus());
        assertEquals(List.of("CREATED"), typesOf(r.getId()));
    }

    @Test
    void 묶음_저장을_켜도_정상_흐름의_기록은_그대로다() {
        GatewayService gateway = service(audit);
        GatewayRequest r = gateway.create("ops-kim", LOG, null, null);

        GatewayRequest after = gateway.decide("sec-park", r.getId(), true, "샘플의 예시 값", r.getPackageSha256(), true);

        assertNotNull(after.getAnswer());
        assertEquals(List.of("CREATED", "APPROVED", "SENT"), typesOf(r.getId()));
    }
}
