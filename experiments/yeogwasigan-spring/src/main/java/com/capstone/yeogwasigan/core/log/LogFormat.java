package com.capstone.yeogwasigan.core.log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 로그 형식 처리: 파싱 · 평탄화(flatten) · 복원(unflatten) · 직렬화.
 *
 * <h3>내부 표준 형식 (레코드 1개 = JSON 1줄)</h3>
 * OpenTelemetry 로그의 3층 구조(resource / scope / logRecord)를 그대로 따른다.
 * <pre>
 * {"resource":  {"service.name": "payment", "host.name": "...", ...},
 *  "scope":     {"name": "...", "version": "..."},
 *  "logRecord": {"timeUnixNano": "...", "severityText": "ERROR", "body": "...",
 *                "traceId": "...", "spanId": "...", "attributes": {"user.email": "...", ...}}}
 * </pre>
 *
 * <h3>필드 경로(path) 규칙 — Purpose Template 의 필드 이름과 1:1 대응</h3>
 * <pre>
 * resource 키             → resource.&lt;키&gt;              예) resource.service.name
 * scope 키                → scope.&lt;키&gt;                 예) scope.name
 * logRecord 키            → logRecord.&lt;키&gt;             예) logRecord.body
 * logRecord.attributes 키 → logRecord.attributes.&lt;키&gt;  예) logRecord.attributes.user.email
 * </pre>
 *
 * OTel Collector 의 file exporter 가 내보내는 원본 OTLP/JSON ({"resourceLogs": [...]}) 도 그대로 넣을 수 있다.
 * {@link #parse(String)} 가 자동으로 위 내부 형식으로 펼친다. (실제 로그로 교체할 때를 대비)
 * 같은 파일에 섞여 있는 {"resourceSpans": ...} · {"resourceMetrics": ...} 줄은 건너뛴다. 실험 대상은 로그뿐이다.
 */
public final class LogFormat {

    public static final List<String> LAYERS = List.of("resource", "scope", "logRecord");

    /** file exporter 가 로그와 한 파일에 섞어 쓰는 로그 외 신호. 이런 줄은 읽지 않고 건너뛴다. */
    private static final List<String> OTLP_NON_LOG_KEYS = List.of("resourceSpans", "resourceMetrics");

    /** 파싱·직렬화에 쓰는 매퍼. Map 은 LinkedHashMap 이라 키 순서가 원본 그대로 유지된다. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private LogFormat() {
    }

    // ------------------------------------------------------------------
    // 파싱
    // ------------------------------------------------------------------

    /** JSONL 텍스트(또는 JSON 배열 / OTLP JSON)를 내부 레코드 목록으로 변환한다. */
    public static List<Map<String, Object>> parse(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            throw new LogParseException("로그가 비어 있습니다.");
        }

        List<JsonNode> nodes = new ArrayList<>();
        try {
            // 1) 전체가 하나의 JSON(배열 또는 OTLP 객체)인 경우
            JsonNode whole = MAPPER.readTree(trimmed);
            if (whole.isArray()) {
                whole.forEach(nodes::add);
            } else {
                nodes.add(whole);
            }
        } catch (JsonProcessingException notSingleJson) {
            // 2) JSONL: 한 줄에 JSON 하나
            String[] lines = trimmed.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].strip();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    nodes.add(MAPPER.readTree(line));
                } catch (JsonProcessingException e) {
                    throw new LogParseException((i + 1) + "번째 줄이 올바른 JSON이 아닙니다: " + e.getOriginalMessage());
                }
            }
        }

        List<Map<String, Object>> records = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (!node.isObject()) {
                throw new LogParseException("각 줄은 JSON 객체여야 합니다.");
            }
            if (!node.has("resourceLogs") && OTLP_NON_LOG_KEYS.stream().anyMatch(node::has)) {
                continue;   // span·metric 줄은 실험 대상이 아니다(로그만 사용)
            }
            Map<String, Object> obj = MAPPER.convertValue(node, MAP_TYPE);
            if (obj.containsKey("resourceLogs")) {
                records.addAll(expandOtlp(obj));
            } else if (LAYERS.stream().anyMatch(obj::containsKey)) {
                Map<String, Object> rec = new LinkedHashMap<>();
                for (String layer : LAYERS) {
                    rec.put(layer, asMap(obj.get(layer)));
                }
                records.add(rec);
            } else {
                throw new LogParseException(
                        "resource / scope / logRecord 3층 구조 또는 OTLP(resourceLogs) 형식이 아닙니다.");
            }
        }
        if (records.isEmpty()) {
            throw new LogParseException("로그 레코드가 없습니다. (span·metric 줄만 있거나 logRecords 가 비어 있음)");
        }
        return records;
    }

    /** OTLP AnyValue({"stringValue": ...} 등)를 자바 값으로 변환. */
    @SuppressWarnings("unchecked")
    private static Object anyValue(Object v) {
        if (!(v instanceof Map<?, ?> m)) {
            return v;
        }
        if (m.containsKey("stringValue")) {
            return m.get("stringValue");
        }
        if (m.containsKey("intValue")) {
            return Long.parseLong(String.valueOf(m.get("intValue")));
        }
        if (m.containsKey("doubleValue")) {
            return Double.parseDouble(String.valueOf(m.get("doubleValue")));
        }
        if (m.containsKey("boolValue")) {
            return Boolean.parseBoolean(String.valueOf(m.get("boolValue")));
        }
        if (m.containsKey("bytesValue")) {
            return m.get("bytesValue");
        }
        if (m.containsKey("arrayValue")) {
            List<Object> values = (List<Object>) asMap(m.get("arrayValue")).getOrDefault("values", List.of());
            return values.stream().map(LogFormat::anyValue).toList();
        }
        if (m.containsKey("kvlistValue")) {
            return kvList((List<Object>) asMap(m.get("kvlistValue")).get("values"));
        }
        return v;
    }

    private static Map<String, Object> kvList(List<Object> items) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (items == null) {
            return out;
        }
        for (Object item : items) {
            Map<String, Object> kv = asMap(item);
            out.put(String.valueOf(kv.get("key")), anyValue(kv.get("value")));
        }
        return out;
    }

    /** OTLP/JSON(resourceLogs → scopeLogs → logRecords)을 레코드 목록으로 펼친다. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> expandOtlp(Map<String, Object> obj) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object rlObj : (List<Object>) obj.getOrDefault("resourceLogs", List.of())) {
            Map<String, Object> rl = asMap(rlObj);
            Map<String, Object> resource = kvList((List<Object>) asMap(rl.get("resource")).get("attributes"));
            for (Object slObj : (List<Object>) rl.getOrDefault("scopeLogs", List.of())) {
                Map<String, Object> sl = asMap(slObj);
                Map<String, Object> sc = asMap(sl.get("scope"));
                Map<String, Object> scope = new LinkedHashMap<>();
                sc.forEach((k, v) -> {
                    if (!"attributes".equals(k)) {
                        scope.put(k, v);
                    }
                });
                if (sc.get("attributes") instanceof List<?> attrs && !attrs.isEmpty()) {
                    scope.put("attributes", kvList((List<Object>) attrs));
                }
                for (Object lrObj : (List<Object>) sl.getOrDefault("logRecords", List.of())) {
                    Map<String, Object> lr = asMap(lrObj);
                    Map<String, Object> rec = new LinkedHashMap<>();
                    lr.forEach((k, v) -> {
                        if (!"attributes".equals(k) && !"body".equals(k)) {
                            rec.put(k, v);
                        }
                    });
                    if (lr.containsKey("body")) {
                        rec.put("body", anyValue(lr.get("body")));
                    }
                    if (lr.get("attributes") instanceof List<?> attrs && !attrs.isEmpty()) {
                        rec.put("attributes", kvList((List<Object>) attrs));
                    }
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("resource", new LinkedHashMap<>(resource));
                    record.put("scope", new LinkedHashMap<>(scope));
                    record.put("logRecord", rec);
                    out.add(record);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------
    // 평탄화 / 복원
    // ------------------------------------------------------------------

    /** 레코드를 {필드경로: 값} 으로 펼친다. 순서는 원본 순서를 유지한다. */
    public static Map<String, Object> flatten(Map<String, Object> record) {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (String layer : LAYERS) {
            if (!(record.get(layer) instanceof Map<?, ?> layerMap)) {
                continue;
            }
            for (Map.Entry<?, ?> e : layerMap.entrySet()) {
                String key = String.valueOf(e.getKey());
                if ("attributes".equals(key) && !"resource".equals(layer) && e.getValue() instanceof Map<?, ?> attrs) {
                    for (Map.Entry<?, ?> a : attrs.entrySet()) {
                        flat.put(layer + ".attributes." + a.getKey(), a.getValue());
                    }
                } else {
                    flat.put(layer + "." + key, e.getValue());
                }
            }
        }
        return flat;
    }

    /** {@link #flatten} 의 역변환. 3층 구조를 항상 유지한다(빈 층은 {}). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> unflatten(Map<String, Object> flat) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (String layer : LAYERS) {
            record.put(layer, new LinkedHashMap<String, Object>());
        }
        for (Map.Entry<String, Object> e : flat.entrySet()) {
            String path = e.getKey();
            int dot = path.indexOf('.');
            if (dot < 0) {
                continue;
            }
            String layer = path.substring(0, dot);
            String rest = path.substring(dot + 1);
            if (!record.containsKey(layer)) {
                continue;
            }
            Map<String, Object> layerMap = (Map<String, Object>) record.get(layer);
            if (!"resource".equals(layer) && rest.startsWith("attributes.")) {
                ((Map<String, Object>) layerMap.computeIfAbsent("attributes", k -> new LinkedHashMap<String, Object>()))
                        .put(rest.substring("attributes.".length()), e.getValue());
            } else {
                layerMap.put(rest, e.getValue());
            }
        }
        return record;
    }

    /** 레코드 전체에 등장하는 서로 다른 필드 경로 목록(등장 순). */
    public static List<String> distinctFields(List<Map<String, Object>> records) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> r : records) {
            seen.addAll(flatten(r).keySet());
        }
        return new ArrayList<>(seen);
    }

    /**
     * 값 안의 모든 문자열에 fn 을 적용한다(Map/List 는 재귀).
     * [B] 빼기와 [C] 담기의 내부 재검사가 "문자열만 검사한다"는 동일한 규칙을 따르도록 이 함수를 공유한다.
     */
    public static Object mapStrings(Object value, UnaryOperator<String> fn) {
        if (value instanceof String s) {
            return fn.apply(s);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) {
                out.add(mapStrings(v, fn));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k, mapStrings(v, fn)));
            return out;
        }
        return value;
    }

    /** 레코드 목록의 깊은 복사. 필터가 입력을 절대 수정하지 않도록 한다. */
    public static List<Map<String, Object>> deepCopy(List<Map<String, Object>> records) {
        List<Map<String, Object>> out = new ArrayList<>(records.size());
        for (Map<String, Object> r : records) {
            out.add(MAPPER.convertValue(r, MAP_TYPE));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 직렬화 — AI 로 실제 전송되는 텍스트. 잔존 채점도 이 텍스트 기준으로 한다.
    // ------------------------------------------------------------------

    /** 레코드 목록을 JSONL(한 줄에 레코드 하나, 공백 없는 JSON, 한글 그대로)로 직렬화한다. */
    public static String serialize(List<Map<String, Object>> records) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(toJson(records.get(i)));
        }
        return sb.toString();
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 직렬화 실패", e);
        }
    }
}
