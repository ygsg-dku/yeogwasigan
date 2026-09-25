package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.preprocess.PreprocessProperties;
import com.capstone.yeogwasigan.preprocess.Preprocessor;

class PreprocessorTest {

    private static Preprocessor pre(int first, int last, int slowest) {
        return new Preprocessor(new PreprocessProperties(null, List.of("x"),
                List.of("load-generator", "otelcol-contrib"), List.of("feature_flag"), List.of("/flagservice/"),
                first, last, slowest, 0));
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
    void 실험_장비_서비스와_스위치_흔적을_뺀다() {
        List<Map<String, Object>> in = List.of(
                rec("load-generator", 1, 9, "User viewing cart", null),
                rec("otelcol-contrib", 2, 9, "Traces", null),
                rec("frontend-proxy", 3, null, envoy("/flagservice/flagd.evaluation.v1.Service/ResolveAll", 200, 1), null),
                rec("shipping", 4, 9, "Requesting quote", Map.of("feature_flag_key", "intlShippingSlowdown", "zip", "94043")));
        Preprocessor.Result r = pre(10, 1, 3).apply(in);

        assertEquals(1, r.records().size());
        Map<String, Object> flat = LogFormat.flatten(r.records().get(0));
        assertEquals("94043", flat.get("logRecord.attributes.zip"));
        assertFalse(flat.containsKey("logRecord.attributes.feature_flag_key"));
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
}
