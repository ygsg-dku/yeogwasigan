package com.capstone.yeogwasigan.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.capstone.yeogwasigan.TestSupport;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.scenario.Scenario;
import com.capstone.yeogwasigan.policy.PolicyEvaluator.Check;
import com.capstone.yeogwasigan.policy.PolicyEvaluator.Decision;
import com.capstone.yeogwasigan.policy.PolicyEvaluator.Input;
import com.capstone.yeogwasigan.policy.PolicyEvaluator.Result;
import com.capstone.yeogwasigan.policy.PolicyEvaluator.Tier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** T-UNIT-04: T0 는 DENIED, T2/T1 은 결정 fixture 대로. (TASK-03-01 완료 기준: T0 DENY, T2/T1 expected decision fixture 통과) */
class PolicyEvaluatorTest {

    private static final String FIXTURE = "/policy/decision_fixture.json";

    private static final Map<String, Tier> REGISTRY = Map.of("t2-internal", Tier.T2, "t1-enterprise", Tier.T1);

    private final PolicyEvaluator evaluator = new PolicyEvaluator(REGISTRY);

    private static JsonNode fixture() throws IOException {
        try (InputStream in = PolicyEvaluatorTest.class.getResourceAsStream(FIXTURE)) {
            return new ObjectMapper().readTree(in);
        }
    }

    /** fixture 의 residual 이름 → Residual Scan 결과. 값은 넣지 않는다(평가기는 상태·사유만 본다). */
    private static ResidualScanner.Result residual(String name) {
        return switch (name) {
            case "PASS" -> ResidualScanner.Result.pass();
            case "SECRET" -> new ResidualScanner.Result(ResidualScanner.Status.DENIED, ResidualScanner.REASON_SECRET_FOUND, List.of());
            case "ATOM" -> new ResidualScanner.Result(ResidualScanner.Status.DENIED, ResidualScanner.REASON_ATOM_FOUND, List.of());
            case "FAILED" -> ResidualScanner.Result.failed();
            default -> throw new IllegalArgumentException("모르는 residual: " + name);
        };
    }

    static Stream<Arguments> cases() throws IOException {
        JsonNode root = fixture();
        return java.util.stream.StreamSupport.stream(root.get("cases").spliterator(), false)
                .map(c -> Arguments.of(c.get("id").asText() + " " + c.get("name").asText(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void 결정_fixture_대로_판정한다(String title, JsonNode c) {
        JsonNode dest = c.get("destination");
        Input in = new Input(dest.isNull() ? null : dest.asText(), c.get("roleAllowed").asBoolean(),
                c.get("purposeActive").asBoolean(), Check.valueOf(c.get("pipeline").asText()),
                residual(c.get("residual").asText()), c.get("highWarnings").asInt(), c.get("approvalAlways").asBoolean());

        Result r = evaluator.evaluate(in);

        assertEquals(Decision.valueOf(c.get("expect").asText()), r.decision(), title);
        assertEquals(c.get("reason").asText(), r.reasonCode(), title);
        assertEquals(PolicyEvaluator.POLICY_VERSION, r.policyVersion());
    }

    @Test
    void fixture_등록부가_테스트_등록부와_같다() throws IOException {
        Map<String, Tier> fromFixture = new LinkedHashMap<>();
        fixture().get("registry").properties().forEach(e -> fromFixture.put(e.getKey(), Tier.valueOf(e.getValue().asText())));
        assertEquals(REGISTRY, fromFixture);
    }

    @Test
    void T0_는_나머지_입력을_어떻게_바꿔도_DENIED_다() {
        // 역할·목적·검사·경고·승인 모드의 모든 조합(2×2×3×2×2 = 48)에서 T0 는 한 번도 통과하지 않는다
        int checked = 0;
        for (boolean role : new boolean[] {true, false}) {
            for (boolean active : new boolean[] {true, false}) {
                for (Check pipeline : Check.values()) {
                    for (int high : new int[] {0, 1}) {
                        for (boolean always : new boolean[] {true, false}) {
                            Result r = evaluator.evaluate(new Input("https://unregistered.example", role, active, pipeline,
                                    ResidualScanner.Result.pass(), high, always));
                            assertEquals(Decision.DENIED, r.decision());
                            assertEquals(PolicyEvaluator.REASON_T0_DESTINATION, r.reasonCode());
                            assertEquals(Tier.T0, r.tier());
                            checked++;
                        }
                    }
                }
            }
        }
        assertEquals(48, checked);
    }

    @Test
    void 등급은_등록부에서만_읽고_없으면_T0_다() {
        assertEquals(Tier.T2, evaluator.tierOf("t2-internal"));
        assertEquals(Tier.T1, evaluator.tierOf("t1-enterprise"));
        assertEquals(Tier.T0, evaluator.tierOf("t1-enterprise "));
        assertEquals(Tier.T0, evaluator.tierOf(""));
        assertEquals(Tier.T0, evaluator.tierOf(null));
    }

    @Test
    void T0_를_등록부에_넣으면_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> new PolicyEvaluator(Map.of("personal", Tier.T0)));
    }

    @Test
    void 평가가_실패하면_통과가_아니라_막는다() {
        // residual 이 비어 있으면(앞 단계 누락) 평가 중 예외 → POLICY_FAILED
        Result r = evaluator.evaluate(new Input("t1-enterprise", true, true, Check.OK, null, 0, false));
        assertEquals(Decision.DENIED, r.decision());
        assertEquals(PolicyEvaluator.REASON_POLICY_FAILED, r.reasonCode());

        Result nullInput = evaluator.evaluate(null);
        assertEquals(Decision.DENIED, nullInput.decision());
        assertEquals(PolicyEvaluator.REASON_POLICY_FAILED, nullInput.reasonCode());
    }

    @Test
    void 실제_파생본_Secret_삽입_사본은_T2_T1_모두_DENIED_이고_원본은_등급대로_통과한다() {
        Scenario sample = TestSupport.scenarios().get(TestSupport.SAMPLE);
        List<Map<String, Object>> clean = TestSupport.allowlist().apply(sample.raw(), sample.id()).output();
        List<Map<String, Object>> copy = LogFormat.deepCopy(clean);
        @SuppressWarnings("unchecked")
        Map<String, Object> logRecord = (Map<String, Object>) copy.get(3).get("logRecord");
        logRecord.put("body", "charge failed, retry with api_key=sk_" + "live_51HqLyJ2eZvKYlo2C0ABCDEFGHIJ");
        ResidualScanner scanner = new ResidualScanner();

        ResidualScanner.Result dirty = scanner.scan(copy);
        for (String dest : List.of("t2-internal", "t1-enterprise")) {
            Result r = evaluator.evaluate(new Input(dest, true, true, Check.OK, dirty, 0, false));
            assertEquals(Decision.DENIED, r.decision(), dest);
            assertEquals(ResidualScanner.REASON_SECRET_FOUND, r.reasonCode(), dest);
        }

        ResidualScanner.Result ok = scanner.scan(clean);
        assertEquals(Decision.PRIVATE_READY, evaluator.evaluate(new Input("t2-internal", true, true, Check.OK, ok, 0, false)).decision());
        assertEquals(Decision.EXTERNAL_READY, evaluator.evaluate(new Input("t1-enterprise", true, true, Check.OK, ok, 0, false)).decision());
        assertEquals(Decision.DENIED, evaluator.evaluate(new Input("chatgpt-personal", true, true, Check.OK, ok, 0, false)).decision());
    }

    @Test
    void 결과에는_사유_코드만_있고_값은_없다() {
        Result r = evaluator.evaluate(new Input("t1-enterprise", true, true, Check.OK, residual("SECRET"), 0, false));
        assertTrue(r.denied());
        assertFalse(r.ready());
        assertFalse(r.toString().contains("sk_"));
    }
}
