package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.capstone.yeogwasigan.core.ai.AiClient;
import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.filter.AllowlistFilter;
import com.capstone.yeogwasigan.core.filter.DenylistFilter;
import com.capstone.yeogwasigan.core.filter.FilterRegistry;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.presidio.PresidioClient;
import com.capstone.yeogwasigan.core.template.TemplateLoader;
import com.capstone.yeogwasigan.core.tokenize.CaseScopedTokenizer;
import com.capstone.yeogwasigan.gateway.AuditEventRepository;
import com.capstone.yeogwasigan.gateway.GatewayProperties;
import com.capstone.yeogwasigan.gateway.GatewayProperties.Role;
import com.capstone.yeogwasigan.gateway.GatewayRequest;
import com.capstone.yeogwasigan.gateway.GatewayRequestRepository;
import com.capstone.yeogwasigan.gateway.GatewayService;
import com.capstone.yeogwasigan.preprocess.PreprocessProperties;

/**
 * 패키지 JSON 형식 확정과 재현성 (7주차: 패키지 JSON 형식 + golden fixture, 같은 로그 재실행 시 해시 동일).
 *
 * <p>같은 로그는 같은 사건 ID(입력 해시에서 만든다)와 고정한 테스트 키로 항상 같은 파생본·같은 해시가 된다.
 * 두 업무 목록(INCIDENT_ANALYSIS, INCIDENT_REPORT_DRAFT)은 같은 로그에서 서로 다른 패키지를 만든다.
 *
 * <p>golden 파일을 다시 만들 때: 환경변수 GOLDEN_UPDATE=1 로 이 테스트를 돌린 뒤 변경분을 검토해서 커밋한다.
 * 형식을 바꾸는 일이므로 A·C·D 에게 알린다. (docs/spec/15_PACKAGE_FORMAT.md)
 */
@DataJpaTest
class PackageGoldenTest {

    private static final String ANALYSIS = "INCIDENT_ANALYSIS";
    private static final String REPORT = "INCIDENT_REPORT_DRAFT";
    private static final String TEST_SECRET = "golden-test-secret";
    private static final Path GOLDEN = Path.of("src", "test", "resources", "golden", TestSupport.SAMPLE);

    @Autowired GatewayRequestRepository requests;
    @Autowired AuditEventRepository audit;

    private GatewayService gateway;
    private String log;

    @BeforeEach
    void setUp() throws IOException {
        log = Files.readString(Path.of("scenarios", TestSupport.SAMPLE, "raw.jsonl"));
        AppProperties app = new AppProperties(null, null, null,
                new AppProperties.Ai("openai", null, 0, null, true, null, null, null), null, null);
        TemplateLoader templates = templates();
        AllowlistFilter allowlist = new AllowlistFilter(templates, new CaseScopedTokenizer(TEST_SECRET), new PresidioClient(app));
        FilterRegistry filters = new FilterRegistry(List.of(TestSupport.passthrough(),
                new DenylistFilter(new PresidioClient(app), app), allowlist));
        GatewayProperties props = new GatewayProperties(Map.of(
                "ops-kim", List.of(Role.REQUESTER),
                "sec-park", List.of(Role.APPROVER)), List.of("openai"), 30, null, GatewayProperties.ApprovalMode.ALWAYS);
        PreprocessProperties pre = new PreprocessProperties(null, null, null, null, null, null, null,
                0, 0, 0, null, 0, 0, 0);
        gateway = new GatewayService(props, filters, templates, new AiClient(app), pre, requests, audit);
    }

    /** 앱이 실제로 읽는 templates/*.yaml 두 개를 그대로 읽는다. */
    private static TemplateLoader templates() {
        try (var a = PackageGoldenTest.class.getResourceAsStream("/templates/incident_analysis.yaml");
             var b = PackageGoldenTest.class.getResourceAsStream("/templates/incident_report_draft.yaml")) {
            return TemplateLoader.of(List.of(TemplateLoader.parse(a), TemplateLoader.parse(b)));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void 같은_로그를_다시_올리면_파생본_해시가_같고_두_업무_목록은_서로_다른_패키지를_만든다() {
        GatewayRequest first = gateway.create("ops-kim", log, ANALYSIS, null);
        GatewayRequest again = gateway.create("ops-kim", log, ANALYSIS, null);
        GatewayRequest report = gateway.create("ops-kim", log, REPORT, null);

        assertNotEquals(first.getId(), again.getId(), "요청은 서로 다른 요청이다");
        assertEquals(first.getPackageSha256(), again.getPackageSha256(), "같은 로그·같은 목록이면 파생본 해시가 같다");
        assertEquals(LogFormat.serialize(first.getPayload()), LogFormat.serialize(again.getPayload()));

        assertNotEquals(first.getPackageSha256(), report.getPackageSha256(), "목록이 다르면 패키지가 다르다");
        assertTrue(report.getFieldsAfter() < first.getFieldsAfter(), "보고서 초안 목록이 분석 목록보다 적게 담는다");
    }

    @Test
    void 파생본이_golden_fixture와_같다() throws IOException {
        GatewayRequest analysis = gateway.create("ops-kim", log, ANALYSIS, null);
        GatewayRequest report = gateway.create("ops-kim", log, REPORT, null);

        String analysisJson = LogFormat.serialize(analysis.getPayload()) + "\n";
        String reportJson = LogFormat.serialize(report.getPayload()) + "\n";
        String manifest = manifest(analysis, report);

        if ("1".equals(System.getenv("GOLDEN_UPDATE"))) {
            Files.createDirectories(GOLDEN);
            Files.writeString(GOLDEN.resolve(ANALYSIS + ".package.jsonl"), analysisJson);
            Files.writeString(GOLDEN.resolve(REPORT + ".package.jsonl"), reportJson);
            Files.writeString(GOLDEN.resolve("manifest.json"), manifest);
        }

        assertEquals(read(ANALYSIS + ".package.jsonl"), analysisJson, ANALYSIS + " 패키지");
        assertEquals(read(REPORT + ".package.jsonl"), reportJson, REPORT + " 패키지");
        assertEquals(read("manifest.json"), manifest, "manifest(해시·건수)");
    }

    /** git 설정(autocrlf)에 따라 golden 파일이 CRLF 로 체크아웃될 수 있어 줄바꿈을 맞춰 읽는다. */
    private static String read(String name) throws IOException {
        return Files.readString(GOLDEN.resolve(name)).replace("\r\n", "\n");
    }

    private static String manifest(GatewayRequest analysis, GatewayRequest report) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenario", TestSupport.SAMPLE);
        m.put("inputSha256", analysis.getInputSha256());
        m.put("tokenizeKey", "TEST_SECRET=" + TEST_SECRET);
        m.put(ANALYSIS, summary(analysis));
        m.put(REPORT, summary(report));
        return pretty(m);
    }

    private static Map<String, Object> summary(GatewayRequest r) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("records", r.getPayload().size());
        s.put("fieldsBefore", r.getFieldsBefore());
        s.put("fieldsAfter", r.getFieldsAfter());
        s.put("listVersion", r.getListVersion());
        s.put("packageSha256", r.getPackageSha256());
        return s;
    }

    /** 사람이 diff 로 읽기 좋게 한 줄에 키 하나씩. */
    @SuppressWarnings("unchecked")
    private static String pretty(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            sb.append("  \"").append(e.getKey()).append("\": ");
            Object v = e.getValue();
            if (v instanceof Map<?, ?> inner) {
                sb.append(LogFormat.toJson(inner));
            } else {
                sb.append(LogFormat.toJson(v));
            }
            sb.append(++i < m.size() ? ",\n" : "\n");
        }
        return sb.append("}\n").toString();
    }
}
