package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import com.capstone.yeogwasigan.core.ai.AiClient;
import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.filter.DenylistFilter;
import com.capstone.yeogwasigan.core.filter.FilterRegistry;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.presidio.PresidioClient;
import com.capstone.yeogwasigan.gateway.AuditEventRepository;
import com.capstone.yeogwasigan.gateway.GatewayProperties;
import com.capstone.yeogwasigan.gateway.GatewayProperties.Role;
import com.capstone.yeogwasigan.gateway.GatewayRequest;
import com.capstone.yeogwasigan.gateway.GatewayRequestRepository;
import com.capstone.yeogwasigan.gateway.GatewayService;
import com.capstone.yeogwasigan.preprocess.PreprocessProperties;

/**
 * 원문 로그는 DB 에 저장되지 않는다(D8)는 것을 시험으로 확인한다.
 * 샘플 로그의 목록 밖 칸(host.name) 값을 눈에 띄는 문자열로 바꿔 요청한 뒤,
 * 요청 테이블과 감사 테이블에 저장된 칸을 전부 읽어 그 문자열이 없는지 본다.
 */
@DataJpaTest
class RawLogNotStoredTest {

    private static final String SENTINEL = "SENTINEL-ORIG-9F3A";
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
        AppProperties app = new AppProperties(null, null, null,
                new AppProperties.Ai("openai", null, 0, null, true, null, null, null), null, null);
        FilterRegistry filters = new FilterRegistry(List.of(TestSupport.passthrough(),
                new DenylistFilter(new PresidioClient(app), app), TestSupport.allowlistBodyImportantOnly()));
        GatewayProperties props = new GatewayProperties(Map.of(
                "ops-kim", List.of(Role.REQUESTER),
                "sec-park", List.of(Role.APPROVER),
                "lead-kang", List.of(Role.REQUESTER, Role.APPROVER)), List.of("openai"), 30, null,
                GatewayProperties.ApprovalMode.ALWAYS);
        PreprocessProperties pre = new PreprocessProperties(null, null, null, null, null, null, null,
                0, 0, 0, null, 0, 0, 0);
        gateway = new GatewayService(props, filters, TestSupport.templates(), new AiClient(app), pre, requests, audit);
    }

    @Test
    void 원문_로그는_요청_테이블과_감사_테이블_어디에도_저장되지_않는다() {
        String log = LOG.replaceAll("\"host\\.name\"\\s*:\\s*\"[^\"]*\"", "\"host.name\":\"" + SENTINEL + "\"");
        assertTrue(log.contains(SENTINEL), "시험 준비: 샘플 로그에 host.name 칸이 있어야 한다");

        GatewayRequest r = gateway.create("ops-kim", log, null, null);
        assertEquals(LogFormat.parse(log).size(), r.getInputRecords());
        // 샘플 본문에 높음 경고가 있어 오탐 확인과 사유가 필요하다(기존 시험과 같은 방식)
        gateway.decide("sec-park", r.getId(), true, "샘플의 예시 값", r.getPackageSha256(), true);

        // DB 에서 다시 읽는다
        em.flush();
        em.clear();

        StringBuilder stored = new StringBuilder();
        requests.findAll().forEach(x -> stored.append(dump(x)));
        gateway.audit("sec-park", r.getId()).forEach(x -> stored.append(dump(x)));
        String all = stored.toString();

        assertTrue(all.contains(r.getInputSha256()), "시험 확인: 저장된 칸을 실제로 읽었다");
        assertFalse(all.contains(SENTINEL), "원문에만 있던 값이 DB 에 저장됐다");
    }

    /** 엔티티의 모든 칸을 문자열로 모은다. 칸이 늘어도 자동으로 포함된다. */
    private static String dump(Object entity) {
        StringBuilder sb = new StringBuilder();
        for (Field f : entity.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                f.setAccessible(true);
                sb.append(f.getName()).append('=').append(f.get(entity)).append('\n');
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return sb.toString();
    }
}
