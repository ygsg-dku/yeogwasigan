package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.filter.DenylistFilter;
import com.capstone.yeogwasigan.core.filter.FilterResult;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.presidio.PresidioClient;
import com.capstone.yeogwasigan.core.presidio.PresidioUnavailableException;
import com.capstone.yeogwasigan.core.scenario.Scenario;
import com.capstone.yeogwasigan.core.scoring.ResidualScorer;

/** 샘플 시나리오로 세 필터의 동작을 확인한다. (Presidio 컨테이너가 필요한 실제 빼기 수치는 README 참고) */
class FilterPipelineTest {

    private final Scenario s = TestSupport.scenarios().get(TestSupport.SAMPLE);
    private final ResidualScorer scorer = new ResidualScorer();

    @Test
    void 그대로는_모든_필드와_민감값이_나간다() {
        FilterResult r = TestSupport.passthrough().apply(s.raw(), s.id());
        assertEquals(49, r.fieldsBefore());
        assertEquals(49, r.fieldsAfter());
        assertTrue(r.droppedFields().isEmpty());
        assertEquals(22, scorer.count(r.output(), s.injectedPii()));
        assertEquals(LogFormat.serialize(s.raw()), LogFormat.serialize(r.output()));
    }

    @Test
    void 담기는_템플릿에_있는_필드만_나간다() {
        FilterResult r = TestSupport.allowlist().apply(s.raw(), s.id());
        assertEquals(49, r.fieldsBefore());
        assertEquals(10, r.fieldsAfter());  // requiredFields 7 + 원인 속성 중 샘플에 있는 3
        assertTrue(r.droppedFields().contains("logRecord.attributes.user.email"));
        assertTrue(r.droppedFields().contains("resource.host.ip"));

        // 내부 재검사를 하지 않으므로(PROTOCOL 의 담기 = 목록만) body 안의 민감값은 그대로 나간다:
        // 계좌·이메일(2회)·이름·IP = 5회. 목록에 없는 필드(user.email 등)의 값은 나가지 않는다
        ResidualScorer.ResidualResult res = scorer.score(r.output(), s.injectedPii());
        assertEquals(5, res.total());
        assertEquals(Set.of("110-432-778812", "minji.park@example.co.kr", "박민지", "10.20.31.45"),
                res.detail().stream().map(ResidualScorer.Hit::value).collect(Collectors.toSet()));
    }

    @Test
    void 담기_함정_플래그_이름은_attributes_에만_있어서_나가지_않는다() {
        FilterResult r = TestSupport.allowlist().apply(s.raw(), s.id());
        String sent = LogFormat.serialize(r.output());
        assertFalse(sent.contains("paymentFailure"));
        assertTrue(LogFormat.serialize(s.raw()).contains("paymentFailure"));
    }

    @Test
    void 담기_토큰으로도_trace_관계를_추적할_수_있다() {
        FilterResult r = TestSupport.allowlist().apply(s.raw(), s.id());
        List<Map<String, Object>> flats = r.output().stream().map(LogFormat::flatten).toList();

        // payment(8번째 줄) · checkout(9) · frontend(10) 이 같은 trace 토큰을 공유
        Object trace = flats.get(7).get("logRecord.traceId");
        assertTrue(String.valueOf(trace).startsWith("TX_"));
        assertEquals(trace, flats.get(8).get("logRecord.traceId"));
        assertEquals(trace, flats.get(9).get("logRecord.traceId"));

        // payment 의 parentSpanId == checkout 의 spanId (부모-자식 연결 유지)
        assertEquals(flats.get(8).get("logRecord.spanId"), flats.get(7).get("logRecord.parentSpanId"));
        // 최상위 span 의 빈 parentSpanId 는 빈 값 그대로
        assertEquals("", flats.get(9).get("logRecord.parentSpanId"));
    }

    @Test
    void 담기는_담은_필드의_값을_고치지_않는다() {
        FilterResult r = TestSupport.allowlist().apply(s.raw(), s.id());
        assertEquals(LogFormat.flatten(s.raw().get(5)).get("logRecord.body"),
                LogFormat.flatten(r.output().get(5)).get("logRecord.body"));
    }

    @Test
    void 내부_재검사를_켜면_body_안의_이메일_계좌_IP를_토큰으로_바꾼다() {
        // 기본값은 꺼져 있다. 비교 실험용으로 켰을 때 동작만 확인한다
        FilterResult r = TestSupport.allowlistWithInnerScan().apply(s.raw(), s.id());
        String body6 = String.valueOf(LogFormat.flatten(r.output().get(5)).get("logRecord.body"));
        assertFalse(body6.contains("minji.park@example.co.kr"));
        assertFalse(body6.contains("110-432-778812"));
        assertTrue(body6.matches(".*EMAIL_[0-9A-F]{8}.*ACCT_[0-9A-F]{8}.*"));
        String body9 = String.valueOf(LogFormat.flatten(r.output().get(8)).get("logRecord.body"));
        assertTrue(body9.contains("IP_"));
        // 서비스 이름·금액 같은 업무 정보는 남는다
        assertTrue(body6.contains("129,000 KRW"));
    }

    @Test
    void 기본_담기는_본문을_경고_이상_기록에서만_담는다() {
        List<Map<String, Object>> logs = LogFormat.parse(
                "{\"resource\":{\"service.name\":\"cart\"},\"scope\":{},\"logRecord\":{\"severityText\":\"INFO\",\"body\":\"user 1234-abcd added item\"}}\n"
                        + "{\"resource\":{\"service.name\":\"payment\"},\"scope\":{},\"logRecord\":{\"severityText\":\"WARN\",\"body\":\"Payment request failed\"}}\n"
                        + "{\"resource\":{\"service.name\":\"frontend-proxy\"},\"scope\":{},\"logRecord\":{\"body\":\"GET /api/products/X HTTP/1.1\\\" 504 UT\"}}");
        String sent = LogFormat.serialize(TestSupport.allowlistBodyImportantOnly().apply(logs, "t").output());
        assertFalse(sent.contains("1234-abcd"));          // INFO 본문은 빠진다
        assertTrue(sent.contains("Payment request failed")); // WARN 본문은 남는다
        assertTrue(sent.contains("504 UT"));               // 프록시 5xx 본문도 남는다
    }

    @Test
    void 담기는_처음_보는_필드도_버린다() {
        List<Map<String, Object>> logs = LogFormat.parse(
                "{\"resource\":{\"service.name\":\"payment\",\"brand.new.secret\":\"s3cr3t\"},\"scope\":{},"
                        + "\"logRecord\":{\"body\":\"ok\",\"attributes\":{\"new.field\":\"x\"}}}");
        FilterResult r = TestSupport.allowlist().apply(logs, "t");
        assertEquals(Set.of("resource.brand.new.secret", "logRecord.attributes.new.field"), Set.copyOf(r.droppedFields()));
        assertFalse(LogFormat.serialize(r.output()).contains("s3cr3t"));
    }

    @Test
    void 필터는_입력을_수정하지_않는다() {
        String before = LogFormat.serialize(s.raw());
        TestSupport.allowlist().apply(s.raw(), s.id());
        TestSupport.passthrough().apply(s.raw(), s.id());
        assertEquals(before, LogFormat.serialize(s.raw()));
    }

    // ---------------------------------------------------------------
    // 빼기: Presidio 호출부만 가짜로 바꿔서 "필드 구조 유지"와 "실패 시 원본 대체 금지"를 확인
    // ---------------------------------------------------------------

    /** 이메일만 <EMAIL_ADDRESS> 로 바꾸는 가짜 Presidio. */
    static class FakePresidio extends PresidioClient {
        private static final Pattern EMAIL = Pattern.compile("[\\w.]+@[\\w.]+");
        final boolean down;

        FakePresidio(boolean down) {
            super(TestSupport.props());
            this.down = down;
        }

        @Override
        public Anonymized scrub(String text) {
            if (down) {
                throw new PresidioUnavailableException("presidio down", null);
            }
            Matcher m = EMAIL.matcher(text);
            List<Detection> ds = new ArrayList<>();
            while (m.find()) {
                ds.add(new Detection("EMAIL_ADDRESS", m.start(), m.end(), 1.0, m.group()));
            }
            return new Anonymized(EMAIL.matcher(text).replaceAll("<EMAIL_ADDRESS>"), ds,
                    ds.stream().map(Detection::entityType).toList());
        }
    }

    @Test
    void 빼기는_필드를_버리지_않고_탐지된_부분만_바꾼다() {
        AppProperties props = TestSupport.props();
        FilterResult r = new DenylistFilter(new FakePresidio(false), props).apply(s.raw(), s.id());
        assertEquals(49, r.fieldsAfter());
        assertTrue(r.droppedFields().isEmpty());
        String sent = LogFormat.serialize(r.output());
        assertFalse(sent.contains("minji.park@example.co.kr"));
        assertTrue(sent.contains("<EMAIL_ADDRESS>"));
        assertTrue(sent.contains("박민지"));   // 가짜 탐지기는 이름을 모른다 → 그대로 남는다
    }

    @Test
    void 빼기는_Presidio가_없으면_원본을_내보내지_않고_실패한다() {
        DenylistFilter f = new DenylistFilter(new FakePresidio(true), TestSupport.props());
        assertThrows(PresidioUnavailableException.class, () -> f.apply(s.raw(), s.id()));
    }
}
