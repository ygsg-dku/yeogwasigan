package com.capstone.yeogwasigan.preprocess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 원본 수집 파일의 span 줄({"resourceSpans": ...})에서 민감 키 값을 뽑는다. ④ 민감정보 주입의 재료.
 *
 * <p>실험 입력은 로그뿐이지만, 데모는 카드번호·이메일·세션 ID 같은 민감값을 span 에만 남긴다.
 * 그래서 같은 수집의 span 에 실제로 있던 값을 traceId 로 이어지는 로그에 옮겨 넣는다.
 */
public final class SpanPiiExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpanPiiExtractor() {
    }

    /** span 하나의 민감 속성 값. */
    public record SpanValue(String key, String value, String traceId, String service, long startTime) {
    }

    /** JSONL 텍스트에서 keys 에 해당하는 span 속성 값을 시작 시각 순으로 돌려준다. span 줄이 없으면 빈 목록. */
    public static List<SpanValue> extract(String text, Set<String> keys) {
        List<SpanValue> out = new ArrayList<>();
        if (text == null || keys.isEmpty()) {
            return out;
        }
        for (String line : text.split("\\R")) {
            if (!line.contains("\"resourceSpans\"")) {
                continue;
            }
            JsonNode root;
            try {
                root = MAPPER.readTree(line);
            } catch (JsonProcessingException e) {
                continue;   // 잘린 줄 등은 LogFormat.parse 가 이미 판단한다
            }
            for (JsonNode rs : root.path("resourceSpans")) {
                String service = "";
                for (JsonNode a : rs.path("resource").path("attributes")) {
                    if ("service.name".equals(a.path("key").asText())) {
                        service = scalar(a.path("value"));
                    }
                }
                for (JsonNode ss : rs.path("scopeSpans")) {
                    for (JsonNode sp : ss.path("spans")) {
                        for (JsonNode a : sp.path("attributes")) {
                            String key = a.path("key").asText();
                            if (keys.contains(key)) {
                                out.add(new SpanValue(key, scalar(a.path("value")), sp.path("traceId").asText(""),
                                        service, sp.path("startTimeUnixNano").asLong(0)));
                            }
                        }
                    }
                }
            }
        }
        out.sort(Comparator.comparingLong(SpanValue::startTime));
        return out;
    }

    /** OTLP AnyValue 의 스칼라 값을 문자열로. */
    private static String scalar(JsonNode v) {
        for (String k : List.of("stringValue", "intValue", "doubleValue", "boolValue")) {
            if (v.has(k)) {
                return v.get(k).asText();
            }
        }
        return "";
    }
}
