package com.capstone.yeogwasigan.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.capstone.yeogwasigan.TestSupport;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.scenario.Scenario;
import com.capstone.yeogwasigan.policy.ResidualScanner.Kind;
import com.capstone.yeogwasigan.policy.ResidualScanner.Result;
import com.capstone.yeogwasigan.policy.ResidualScanner.Status;

/** T-INT-02: Secret 이 남은 파생본은 DENIED 다. (TASK-03-02 완료 기준: Secret 삽입 사본 → DENIED) */
class ResidualScannerTest {

    private final ResidualScanner scanner = new ResidualScanner();
    private final Scenario sample = TestSupport.scenarios().get(TestSupport.SAMPLE);

    /** 담기를 거친 실제 파생본(깨끗한 것). */
    private List<Map<String, Object>> cleanPackage() {
        return TestSupport.allowlist().apply(sample.raw(), sample.id()).output();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> logRecord(Map<String, Object> record) {
        return (Map<String, Object>) record.get("logRecord");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> record) {
        return (Map<String, Object>) logRecord(record).computeIfAbsent("attributes", k -> new LinkedHashMap<String, Object>());
    }

    @Test
    void 깨끗한_파생본은_통과한다() {
        Result r = scanner.scan(cleanPackage());

        assertEquals(Status.PASS, r.status());
        assertTrue(r.passed());
        assertEquals(0, r.secretCount());
        assertTrue(r.findings().isEmpty());
    }

    @Test
    void Secret_삽입_사본은_DENIED_이고_원본은_그대로_통과한다() {
        List<Map<String, Object>> original = cleanPackage();
        List<Map<String, Object>> copy = LogFormat.deepCopy(original);
        String bodySecret = "sk_" + "live_51HqLyJ2eZvKYlo2C0ABCDEFGHIJ";
        String fieldSecret = "n0t-a-real-but-looks-like-one";
        logRecord(copy.get(3)).put("body", "charge failed, retry with api_key=" + bodySecret);
        attributes(copy.get(5)).put("api_secret", fieldSecret);

        Result r = scanner.scan(copy);

        assertEquals(Status.DENIED, r.status());
        assertEquals(ResidualScanner.REASON_SECRET_FOUND, r.reasonCode());
        assertTrue(r.secretCount() >= 2);
        // 발견 위치(기록 순번·칸)와 규칙이 reason 에 남는다
        assertTrue(r.findings().stream().anyMatch(f -> f.record() == 3 && f.field().equals("logRecord.body")));
        assertTrue(r.findings().stream().anyMatch(f -> f.record() == 5
                && f.field().equals("logRecord.attributes.api_secret") && f.ruleId().equals("SECRET_FIELD_NAME")));
        // 결과(감사 기록·화면용)에 비밀값이 다시 새지 않는다
        String shown = r.toString();
        assertFalse(shown.contains(bodySecret));
        assertFalse(shown.contains(fieldSecret));

        // 사본만 막히고 원본 파생본은 그대로 통과한다
        assertTrue(scanner.scan(original).passed());
    }

    @Test
    void 비밀값_모양별로_잡는다() {
        Map<String, String> samples = new LinkedHashMap<>();
        samples.put("STRIPE_KEY", "key sk_" + "test_4eC39HqLyjWDarjtT1zdp7dc");
        samples.put("SK_API_KEY", "OPENAI sk-" + "proj-abcdefghijklmnopqrstuvwxyz0123456789");
        samples.put("AWS_ACCESS_KEY", "id AKIA" + "IOSFODNN7EXAMPLE used");
        samples.put("BEARER_TOKEN", "Authorization header Bearer 3f9c2b7a1d4e8f60a5b7c9d1e2f3a4b5");
        samples.put("JWT", "t=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r");
        samples.put("PRIVATE_KEY", "-----BEGIN RSA " + "PRIVATE KEY-----\nMIIEow...");
        samples.put("URL_CREDENTIAL", "connect postgres://app:hunter2pw@db.internal:5432/pay failed");
        samples.put("SECRET_ASSIGNMENT", "login ok password=Sup3rS3cret!");

        samples.forEach((rule, text) -> {
            List<Map<String, Object>> copy = LogFormat.deepCopy(cleanPackage());
            logRecord(copy.get(0)).put("body", text);
            Result r = scanner.scan(copy);
            assertEquals(Status.DENIED, r.status(), rule);
            assertTrue(r.findings().stream().anyMatch(f -> f.ruleId().equals(rule)), rule + " 규칙이 걸려야 한다: " + r.findings());
        });
    }

    @Test
    void 이미_가려진_값과_토큰은_남은_비밀값이_아니다() {
        List<Map<String, Object>> copy = LogFormat.deepCopy(cleanPackage());
        attributes(copy.get(0)).put("api_secret", "[SECRET REMOVED]");
        attributes(copy.get(1)).put("password", "***");
        attributes(copy.get(2)).put("authorization", "TX_9166A5F1");
        logRecord(copy.get(3)).put("body", "retry with api_key={api_key} password=REDACTED");

        assertTrue(scanner.scan(copy).passed());
    }

    @Test
    void 금지_atom_칸은_DENIED_다() {
        List<Map<String, Object>> copy = LogFormat.deepCopy(cleanPackage());
        attributes(copy.get(2)).put("customer_name", "박민지");

        Result r = scanner.scan(copy);

        assertEquals(Status.DENIED, r.status());
        assertEquals(ResidualScanner.REASON_ATOM_FOUND, r.reasonCode());
        assertEquals(0, r.secretCount());
        assertEquals(Kind.PROHIBITED_ATOM, r.findings().get(0).kind());
    }

    @Test
    void 스캔이_실패하면_통과가_아니라_막는다() {
        Result r = scanner.scan(null);
        assertEquals(Status.DENIED, r.status());
        assertEquals(ResidualScanner.REASON_SCAN_FAILED, r.reasonCode());

        // 레코드 안에 풀 수 없는 값이 있어도 PASS 로 넘기지 않는다
        List<Map<String, Object>> broken = new java.util.ArrayList<>();
        broken.add(null);
        assertEquals(ResidualScanner.REASON_SCAN_FAILED, scanner.scan(broken).reasonCode());
    }

    @Test
    void 실제_시나리오_파생본에서_오탐이_없다() throws IOException {
        // 수집 로그에는 비밀값이 없다(키·토큰·password 문자열 0건 확인). 하나라도 걸리면 규칙이 너무 넓은 것이다.
        // 담기 파생본은 통째로 PASS 여야 한다. 원본(그대로)은 샘플처럼 account_number 칸을 일부러 넣은 시나리오가 있어
        // 금지 atom 은 걸릴 수 있으므로, 원본에서는 Secret 이 0건인지만 본다.
        Path root = Path.of("scenarios");
        int checked = 0;
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path raw = dir.resolve("raw.jsonl");
                if (!Files.isRegularFile(raw)) {
                    continue;
                }
                List<Map<String, Object>> logs = LogFormat.parse(Files.readString(raw));
                String id = dir.getFileName().toString();
                Result kept = scanner.scan(TestSupport.allowlist().apply(logs, id).output());
                Result everything = scanner.scan(logs);
                assertTrue(kept.passed(), id + " 담기 파생본: " + kept.findings());
                assertEquals(0, everything.secretCount(), id + " 원본: " + everything.findings());
                checked++;
            }
        }
        assertTrue(checked >= 7, "시나리오를 읽지 못했다: " + checked);
    }
}
