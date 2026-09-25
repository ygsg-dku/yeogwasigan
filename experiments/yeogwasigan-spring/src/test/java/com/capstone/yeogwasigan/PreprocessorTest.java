package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.preprocess.PreprocessProperties;
import com.capstone.yeogwasigan.preprocess.Preprocessor;
import com.capstone.yeogwasigan.preprocess.SpanPiiExtractor;
import com.capstone.yeogwasigan.preprocess.SpanPiiExtractor.SpanValue;

class PreprocessorTest {

    private static Preprocessor pre(int first, int last, int slowest) {
        return new Preprocessor(new PreprocessProperties(null, List.of("x"),
                List.of("load-generator", "otelcol-contrib"), List.of("feature_flag"), List.of("/flagservice/"),
                List.of(" Feature Flag Enabled"), List.of("feature flag", "FeatureFlag", "feature_flag", "kafkaQueueProblems"),
                first, last, slowest, List.of(), 0, 0, 0));
    }

    private static Map<String, Object> rec(String service, long t, Integer sev, String body, Map<String, Object> attrs) {
        Map<String, Object> lr = new LinkedHashMap<>();
        lr.put("timeUnixNano", String.valueOf(t));
        if (sev != null) {
            lr.put("severityNumber", sev);
        }
        lr.put("body", body);
        if (attrs != null) {
            lr.put("attributes", new LinkedHashMap<>(attrs));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resource", new LinkedHashMap<>(Map.of("service.name", service)));
        r.put("scope", new LinkedHashMap<>());
        r.put("logRecord", lr);
        return r;
    }

    private static String envoy(String path, int status, int ms) {
        return "[2026-09-23T09:40:39.124Z] \"GET " + path + "?q=1 HTTP/1.1\" " + status
                + " - via_upstream - \"-\" 0 50 " + ms + " " + ms + " \"-\" \"UA\"";
    }

    private static List<String> bodies(List<Map<String, Object>> recs) {
        return recs.stream().map(r -> String.valueOf(LogFormat.flatten(r).get("logRecord.body"))).toList();
    }

    @Test
    void 실험_장비_서비스와_flagd_호출_로그를_뺀다() {
        List<Map<String, Object>> in = List.of(
                rec("load-generator", 1, 9, "User viewing cart", null),
                rec("otelcol-contrib", 2, 9, "Traces", null),
                rec("frontend-proxy", 3, null, envoy("/flagservice/flagd.evaluation.v1.Service/ResolveAll", 200, 1), null),
                rec("shipping", 4, 9, "Requesting quote", Map.of("zip", "94043")));
        Preprocessor.Result r = pre(10, 1, 3).apply(in);
        assertEquals(List.of("Requesting quote"), bodies(r.records()));
    }

    @Test
    void 통째로_빼지_않은_로그에서는_스위치_속성_키만_지운다() {
        // drop 목록에 없는 접두어(demo.feature_flag.)만 걸리도록 따로 구성
        Preprocessor p = new Preprocessor(new PreprocessProperties(null, List.of("x"), List.of(),
                List.of("demo.feature_flag."), List.of(), List.of(), List.of(), 10, 1, 3, List.of(), 0, 0, 0));
        Preprocessor.Result r = p.apply(List.of(
                rec("shipping", 1, 9, "Requesting quote", Map.of("demo.feature_flag.key", "x", "zip", "94043"))));
        Map<String, Object> flat = LogFormat.flatten(r.records().get(0));
        assertEquals("94043", flat.get("logRecord.attributes.zip"));
        assertFalse(flat.containsKey("logRecord.attributes.demo.feature_flag.key"));
    }

    @Test
    void 스위치_흔적이_있는_로그는_통째로_뺀다() {
        List<Map<String, Object>> in = List.of(
                rec("fraud-detection", 1, 9, "FeatureFlag 'kafkaQueueProblems' is enabled, sleeping 1 second", null),
                rec("shipping", 2, 9, "Delaying international shipment due to intlShippingSlowdown feature flag", null),
                rec("shipping", 3, 9, "", Map.of("feature_flag_key", "intlShippingSlowdown")),
                rec("fraud-detection", 4, 9, "Consumed record with orderId: 1", null));
        Preprocessor.Result r = pre(10, 1, 3).apply(in);
        assertEquals(List.of("Consumed record with orderId: 1"), bodies(r.records()));
    }

    @Test
    void 장애_에러는_남기고_스위치_문구만_지운다() {
        Map<String, Object> err = rec("frontend", 1, 17, "API request failed",
                Map.of("exception.message", "13 INTERNAL: Error: Product Catalog Fail Feature Flag Enabled"));
        Preprocessor.Result r = pre(10, 1, 3).apply(List.of(err));
        assertEquals(1, r.records().size());
        assertEquals("13 INTERNAL: Error: Product Catalog Fail",
                LogFormat.flatten(r.records().get(0)).get("logRecord.attributes.exception.message"));
    }

    @Test
    void 반복은_처음_k건과_마지막_건만_남기고_시간순으로_정렬한다() {
        List<Map<String, Object>> in = new ArrayList<>();
        for (int i = 10; i >= 1; i--) {   // 일부러 역순으로 넣는다
            in.add(rec("product-catalog", i, 9, "Product Found #" + i, null));
        }
        Preprocessor.Result r = pre(2, 1, 0).apply(in);
        assertEquals(List.of("Product Found #1", "Product Found #2", "Product Found #10"), bodies(r.records()));
    }

    @Test
    void WARN_이상과_5xx_는_반복이어도_전부_남긴다() {
        List<Map<String, Object>> in = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            in.add(rec("payment", i, 13, "Payment request failed. Invalid token.", null));
            in.add(rec("frontend", 100 + i, 9, "API request completed",
                    Map.of("url.path", "/api/cart", "http.response.status_code", "500")));
        }
        Preprocessor.Result r = pre(1, 0, 0).apply(in);
        assertEquals(10, r.records().size());
    }

    @Test
    void 같은_메시지라도_상태코드나_경로가_다르면_따로_묶는다() {
        List<Map<String, Object>> in = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            in.add(rec("frontend", i, 9, "API request completed", Map.of("url.path", "/api/products", "http.response.status_code", "200")));
            in.add(rec("frontend", 10 + i, 9, "API request completed", Map.of("url.path", "/api/cart", "http.response.status_code", "200")));
            in.add(rec("frontend", 20 + i, 9, "API request completed", Map.of("url.path", "/api/cart", "http.response.status_code", "404")));
        }
        Preprocessor.Result r = pre(1, 0, 0).apply(in);
        assertEquals(3, r.records().size());
    }

    @Test
    void 프록시_로그는_경로별로_묶고_가장_느린_요청을_남긴다() {
        List<Map<String, Object>> in = new ArrayList<>();
        int[] ms = {10, 12, 9, 5003, 11, 8, 4870, 10};
        for (int i = 0; i < ms.length; i++) {
            in.add(rec("frontend-proxy", i + 1, null, envoy("/api/shipping", 200, ms[i]), null));
        }
        Preprocessor.Result r = pre(1, 0, 2).apply(in);
        List<String> kept = bodies(r.records());
        assertEquals(3, kept.size());   // 처음 1건 + 느린 2건
        assertTrue(kept.stream().anyMatch(b -> b.contains(" 5003 ")));
        assertTrue(kept.stream().anyMatch(b -> b.contains(" 4870 ")));
    }

    @Test
    void 값은_바꾸지_않는다() {
        Map<String, Object> original = rec("cart", 1, 9, "GetCartAsync called with userId=u-58213", Map.of("app.user.id", "u-58213"));
        Preprocessor.Result r = pre(10, 1, 3).apply(List.of(original));
        assertEquals(LogFormat.toJson(original), LogFormat.toJson(r.records().get(0)));
    }

    // ---------------- ④ 민감정보 주입 ----------------

    private static Preprocessor piiPre(int maxPerKey) {
        return new Preprocessor(new PreprocessProperties(null, List.of("x"), List.of(), List.of(), List.of(),
                List.of(), List.of(), 10, 1, 3,
                List.of("user.email", "demo.payment.card_number", "demo.payment.card_cvv", "transactionId"), 6, maxPerKey, 0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> traced(String service, long t, String body, String traceId, Map<String, Object> attrs) {
        Map<String, Object> r = rec(service, t, 9, body, attrs);
        ((Map<String, Object>) r.get("logRecord")).put("traceId", traceId);
        return r;
    }

    @Test
    void span_값을_같은_traceId_로그에_속성과_본문으로_번갈아_넣는다() {
        List<Map<String, Object>> logs = List.of(
                traced("frontend", 1, "POST /api/checkout received", "T1", null),
                traced("checkout", 2, "[PlaceOrder] user_id=u1", "T1", null),
                traced("checkout", 3, "[PlaceOrder] user_id=u2", "T2", null));
        List<SpanValue> spans = List.of(
                new SpanValue("user.email", "reed@example.com", "T1", "checkout", 1),
                new SpanValue("user.email", "jack@example.com", "T2", "checkout", 2),
                new SpanValue("demo.payment.card_cvv", "793", "T1", "checkout", 3),        // 6자 미만 → 제외
                new SpanValue("user.email", "nobody@example.com", "T9", "checkout", 4));  // 이어지는 로그 없음
        Preprocessor.Result r = piiPre(5).apply(logs, spans);

        Map<String, Object> f1 = LogFormat.flatten(r.records().get(1));   // T1 의 checkout 로그 (서비스 일치 우선)
        Map<String, Object> f2 = LogFormat.flatten(r.records().get(2));
        assertEquals("reed@example.com", f1.get("logRecord.attributes.user.email"));            // 첫 값: 속성
        assertEquals("[PlaceOrder] user_id=u2 user.email=jack@example.com", f2.get("logRecord.body"));  // 두번째: 본문
        assertEquals(List.of("reed@example.com", "jack@example.com"), r.pii().stream().map(p -> p.value()).toList());
        assertFalse(LogFormat.serialize(r.records()).contains("793"));
    }

    @Test
    void 원래_로그에_있던_민감값은_자연_발생으로_세고_다시_넣지_않는다() {
        List<Map<String, Object>> logs = List.of(
                traced("payment", 1, "Transaction complete.", "T1", Map.of("transactionId", "05bac2b1-39ac-41d5")));
        List<SpanValue> spans = List.of(new SpanValue("transactionId", "05bac2b1-39ac-41d5", "T1", "payment", 1));
        Preprocessor.Result r = piiPre(5).apply(logs, spans);
        assertEquals(1, r.pii().size());
        assertTrue(r.pii().get(0).description().startsWith("자연 발생"));
        assertEquals(1, LogFormat.serialize(r.records()).split("05bac2b1-39ac-41d5", -1).length - 1);
    }

    @Test
    void 키마다_넣는_개수는_상한을_넘지_않는다() {
        List<Map<String, Object>> logs = new ArrayList<>();
        List<SpanValue> spans = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            logs.add(traced("checkout", i, "order " + i, "T" + i, null));
            spans.add(new SpanValue("demo.payment.card_number", "4929-0000-0000-000" + i, "T" + i, "checkout", i));
        }
        assertEquals(2, piiPre(2).apply(logs, spans).pii().size());
    }

    @Test
    void span_줄에서_민감_키_값을_뽑는다() {
        String text = """
                {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"checkout"}}]},"scopeSpans":[{"spans":[{"traceId":"T1","startTimeUnixNano":"5","attributes":[{"key":"user.email","value":{"stringValue":"reed@example.com"}},{"key":"app.x","value":{"stringValue":"y"}}]}]}]}]}
                {"resourceLogs":[]}
                """;
        List<SpanValue> v = SpanPiiExtractor.extract(text, Set.of("user.email"));
        assertEquals(List.of(new SpanValue("user.email", "reed@example.com", "T1", "checkout", 5)), v);
    }
}
