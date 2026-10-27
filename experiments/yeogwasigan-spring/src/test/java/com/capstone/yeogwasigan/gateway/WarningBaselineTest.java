package com.capstone.yeogwasigan.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.capstone.yeogwasigan.TestSupport;
import com.capstone.yeogwasigan.core.config.ExperimentConstants;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.scenario.PiiItem;
import com.capstone.yeogwasigan.core.scenario.Scenario;

/**
 * 경고 기준 v0.1 의 기준선 (docs/spec/14_APPROVAL_AND_WARNING.md "측정").
 *
 * <p>파일럿 S1~S6 의 담기 파생본에서:
 * <ul>
 *   <li>재현율 — 파생본에 남은 민감값(injected_pii.yaml) 횟수 = 그 값을 짚은 높음 경고 수</li>
 *   <li>정밀도 — 높음 경고가 모두 실제 민감값 (오탐 0)</li>
 *   <li>승인 필요 — 높음 경고가 있는 요청은 S1, S6 뿐</li>
 * </ul>
 * 규칙 설계에 쓴 데이터라 일반화 근거는 아니다. 기준을 바꾸면 이 숫자가 바뀌는지 먼저 본다.
 */
class WarningBaselineTest {

    @ParameterizedTest(name = "{0}: 남은 민감값 {1}회 · 높음 {2}건(모두 진짜) · 낮음 {3}건")
    @CsvSource({
            "S1_paymentFailure,                 3,  3,  0",
            "S2_paymentUnreachable,             0,  0, 10",
            "S3_productCatalogLockContention,   0,  0, 17",
            "S4_kafkaQueueProblems,             0,  0,  0",
            "S5_intlShippingSlowdown,           0,  0,  0",
            "S6_productCatalogFailure,         25, 25, 40"})
    void 남은_민감값은_높음_경고가_모두_짚고_높음_경고에_오탐이_없다(String id, int residual, int high, int low) {
        Scenario s = TestSupport.scenarios().get(id);
        List<Map<String, Object>> payload = TestSupport.allowlistBodyImportantOnly()
                .apply(s.raw(), id, ExperimentConstants.PURPOSE_ID).output();
        Set<String> pii = new HashSet<>();
        for (PiiItem p : s.injectedPii()) {
            pii.add(p.value());
        }
        String text = LogFormat.serialize(payload);
        int remaining = 0;
        for (String v : pii) {
            for (int i = text.indexOf(v); i >= 0; i = text.indexOf(v, i + v.length())) {
                remaining++;
            }
        }
        List<WarningRules.Finding> findings = WarningRules.scan(payload);
        List<WarningRules.Finding> highs = findings.stream().filter(f -> f.level() == WarningRules.Level.HIGH).toList();
        long real = highs.stream().filter(f -> pii.contains(f.value())).count();

        assertEquals(residual, remaining, "파생본에 남은 민감값");
        assertEquals(high, highs.size(), "높음 경고");
        assertEquals(highs.size(), real, "높음 경고는 모두 실제 민감값이어야 한다(오탐 0)");
        assertEquals(remaining, real, "남은 민감값은 모두 높음 경고가 짚어야 한다(재현율 100%)");
        assertEquals(low, findings.size() - highs.size(), "낮음 경고");
    }
}
