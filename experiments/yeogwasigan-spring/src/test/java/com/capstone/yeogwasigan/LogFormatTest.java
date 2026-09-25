package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.log.LogParseException;

class LogFormatTest {

    @Test
    void OTLP_resourceLogs_를_3층_레코드로_펼친다() {
        String otlp = """
                {"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"payment"}}]},
                  "scopeLogs":[{"scope":{"name":"payment","version":"2.0.2"},
                    "logRecords":[{"timeUnixNano":"1757913150034000000","severityText":"ERROR",
                      "body":{"stringValue":"Payment request failed. Invalid token."},
                      "traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"3e1a9c04d8b27f55",
                      "attributes":[{"key":"rpc.grpc.status_code","value":{"intValue":"2"}}]}]}]}]}
                """;
        List<Map<String, Object>> recs = LogFormat.parse(otlp);
        assertEquals(1, recs.size());
        Map<String, Object> flat = LogFormat.flatten(recs.get(0));
        assertEquals("payment", flat.get("resource.service.name"));
        assertEquals("Payment request failed. Invalid token.", flat.get("logRecord.body"));
        assertEquals(2L, flat.get("logRecord.attributes.rpc.grpc.status_code"));
    }

    @Test
    void 로그와_span_이_섞인_JSONL_에서_로그만_읽는다() {
        String mixed = """
                {"resourceSpans":[{"resource":{"attributes":[]},"scopeSpans":[{"spans":[{"name":"charge","attributes":[{"key":"app.payment.card_number","value":{"stringValue":"4111-1111-1111-1111"}}]}]}]}]}
                {"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"payment"}}]},"scopeLogs":[{"scope":{"name":"payment"},"logRecords":[{"severityNumber":13,"body":{"stringValue":"first"}}]}]}]}
                {"resourceMetrics":[{"resource":{"attributes":[]},"scopeMetrics":[]}]}
                {"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"checkout"}}]},"scopeLogs":[{"scope":{"name":"checkout"},"logRecords":[{"body":{"stringValue":"second"}}]}]}]}
                """;
        List<Map<String, Object>> recs = LogFormat.parse(mixed);
        assertEquals(2, recs.size());
        assertEquals("first", LogFormat.flatten(recs.get(0)).get("logRecord.body"));
        assertEquals("checkout", LogFormat.flatten(recs.get(1)).get("resource.service.name"));
        assertTrue(!LogFormat.serialize(recs).contains("4111"), "span 내용이 섞여 들어오면 안 된다");
    }

    @Test
    void span_줄만_있으면_레코드_없음_오류() {
        LogParseException e = assertThrows(LogParseException.class,
                () -> LogFormat.parse("{\"resourceSpans\":[]}\n{\"resourceSpans\":[]}"));
        assertTrue(e.getMessage().contains("로그 레코드가 없습니다"), e.getMessage());
    }

    @Test
    void 평탄화와_복원은_서로_역변환() {
        List<Map<String, Object>> recs = TestSupport.scenarios().get(TestSupport.SAMPLE).raw();
        for (Map<String, Object> r : recs) {
            assertEquals(LogFormat.toJson(r), LogFormat.toJson(LogFormat.unflatten(LogFormat.flatten(r))));
        }
    }

    @Test
    void JSONL_오류는_줄_번호를_알려준다() {
        LogParseException e = assertThrows(LogParseException.class,
                () -> LogFormat.parse("{\"resource\":{}}\n{broken"));
        assertTrue(e.getMessage().startsWith("2번째 줄"), e.getMessage());
    }

    @Test
    void 직렬화는_한글을_그대로_두고_공백이_없다() {
        String s = LogFormat.serialize(LogFormat.parse("{\"resource\":{\"user.name\":\"박민지\"},\"scope\":{},\"logRecord\":{}}"));
        assertEquals("{\"resource\":{\"user.name\":\"박민지\"},\"scope\":{},\"logRecord\":{}}", s);
    }
}
