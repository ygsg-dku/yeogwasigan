package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.capstone.yeogwasigan.core.filter.AllowlistFilter;
import com.capstone.yeogwasigan.core.filter.FilterResult;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.presidio.PresidioClient;
import com.capstone.yeogwasigan.core.scenario.Scenario;
import com.capstone.yeogwasigan.core.template.TemplateLoader;
import com.capstone.yeogwasigan.core.tokenize.CaseScopedTokenizer;

/** 정책 명세 POLICY-02: 보고서 초안(INCIDENT_REPORT_DRAFT)의 timestamp 는 GENERALIZE_TO_WINDOW 다. */
class GeneralizeWindowTest {

    private static AllowlistFilter filter() throws IOException {
        try (var in = GeneralizeWindowTest.class.getResourceAsStream("/templates/incident_report_draft.yaml")) {
            return new AllowlistFilter(TemplateLoader.of(List.of(TemplateLoader.parse(in))),
                    new CaseScopedTokenizer("test-secret"), new PresidioClient(TestSupport.props()));
        }
    }

    @Test
    void 시각은_5분_구간으로_뭉개지고_정밀한_시각은_나가지_않는다() throws IOException {
        Scenario sample = TestSupport.scenarios().get(TestSupport.SAMPLE);
        FilterResult r = filter().apply(sample.raw(), "CASE-TEST", "INCIDENT_REPORT_DRAFT");

        assertFalse(r.output().isEmpty());
        for (Map<String, Object> rec : r.output()) {
            Object t = LogFormat.flatten(rec).get("logRecord.timeUnixNano");
            assertTrue(t instanceof String s && s.endsWith("Z/PT5M"), "구간 표기여야 한다: " + t);
            Instant start = Instant.parse(((String) t).substring(0, ((String) t).indexOf('/')));
            assertEquals(0, start.getEpochSecond() % 300, "5분 경계로 내림");
        }
        String all = LogFormat.serialize(r.output());
        for (Map<String, Object> rec : sample.raw()) {
            Object nanos = LogFormat.flatten(rec).get("logRecord.timeUnixNano");
            assertFalse(all.contains(String.valueOf(nanos)), "원래 나노초 시각이 남으면 안 된다: " + nanos);
        }
    }

    @Test
    void 구간_계산_경계() {
        // 2026-10-05T10:04:59Z → 10:00 구간, 10:05:00Z → 10:05 구간
        assertEquals("2026-10-05T10:00:00Z/PT5M", AllowlistFilter.toWindow("1791194699000000000"));
        assertEquals("2026-10-05T10:05:00Z/PT5M", AllowlistFilter.toWindow(1791194700000000000L));
        assertNull(AllowlistFilter.toWindow("not-a-time"), "읽을 수 없으면 값을 내보내지 않는다");
        assertNull(AllowlistFilter.toWindow(null));
    }
}
