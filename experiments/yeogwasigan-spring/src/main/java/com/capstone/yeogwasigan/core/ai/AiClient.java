package com.capstone.yeogwasigan.core.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.config.ExperimentConstants;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 외부 AI 호출.
 *
 * <ul>
 *   <li>세 조건(A/B/C)에 완전히 동일한 질문·프롬프트 형식·설정을 사용한다.
 *       달라지는 것은 오직 "필터를 거친 로그" 부분뿐이다. ({@link #buildPrompt})</li>
 *   <li>temperature=0 고정, 모델명은 설정(YG_AI_MODEL)으로 고정.</li>
 *   <li>API 키가 없으면(또는 YG_AI_MOCK=true) mock 응답을 돌려준다. 개발·리허설용이며
 *       mock 응답은 실험 결과로 쓰면 안 된다. (run_meta.json 에 모드가 기록된다)</li>
 *   <li>호출 오류는 예외 대신 mode=error 응답으로 돌려준다. 실험 루프가 중간에 멈추지 않도록.</li>
 * </ul>
 *
 * 지원 제공자: anthropic (ANTHROPIC_API_KEY), openai (OPENAI_API_KEY)
 */
@Component
public class AiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> DEFAULT_MODELS = Map.of(
            "anthropic", "claude-sonnet-4-5",
            "openai", "gpt-5.1-2025-11-13");

    private final AppProperties.Ai props;
    private final String model;
    private final RestClient http;

    public AiClient(AppProperties appProps) {
        this.props = appProps.ai();
        this.model = props.model() == null || props.model().isBlank()
                ? DEFAULT_MODELS.getOrDefault(props.provider(), "")
                : props.model().trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(props.timeout());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** 질문 + 로그. 세 조건 모두 이 메서드 하나로 프롬프트를 만든다. */
    public static String buildPrompt(List<Map<String, Object>> logs, String question) {
        return question + "\n\n```jsonl\n" + LogFormat.serialize(logs) + "\n```";
    }

    /** live | mock */
    public String mode() {
        if (props.mock()) {
            return "mock";
        }
        String key = apiKey();
        return key != null && !key.isBlank() ? "live" : "mock";
    }

    public String provider() {
        return props.provider();
    }

    public String model() {
        return model;
    }

    public int maxTokens() {
        return props.maxTokens();
    }

    /** 요청에 실어 보내는 reasoning_effort. OpenAI 추론 모델(gpt-5*, o*)에만 해당하고, 나머지는 null. */
    public String reasoningEffort() {
        boolean reasoningModel = "openai".equals(props.provider())
                && (model.startsWith("gpt-5") || model.matches("o\\d.*"));
        return reasoningModel ? ExperimentConstants.REASONING_EFFORT : null;
    }

    /** 고정 질문으로 묻는다. */
    public AiAnswer ask(List<Map<String, Object>> logs) {
        return ask(logs, ExperimentConstants.FIXED_QUESTION);
    }

    public AiAnswer ask(List<Map<String, Object>> logs, String question) {
        String prompt = buildPrompt(logs, question);
        String mode = mode();
        if ("mock".equals(mode)) {
            return answer(mockAnswer(logs), "mock", prompt);
        }
        try {
            String text = switch (props.provider()) {
                case "anthropic" -> callAnthropic(prompt);
                case "openai" -> callOpenAi(prompt);
                default -> throw new IllegalArgumentException("지원하지 않는 YG_AI_PROVIDER: " + props.provider());
            };
            return answer(text, "live", prompt);
        } catch (RuntimeException e) {   // 실험 루프가 멈추지 않도록 오류도 응답으로 기록
            return answer("[AI 호출 오류] " + e.getClass().getSimpleName() + ": " + e.getMessage(), "error", prompt);
        }
    }

    private AiAnswer answer(String text, String mode, String prompt) {
        return new AiAnswer(text, mode, props.provider(), model, ExperimentConstants.TEMPERATURE, prompt.length());
    }

    private String apiKey() {
        return switch (props.provider()) {
            case "anthropic" -> props.anthropicApiKey();
            case "openai" -> props.openaiApiKey();
            default -> null;
        };
    }

    private String callAnthropic(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", props.maxTokens());
        body.put("temperature", ExperimentConstants.TEMPERATURE);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        String raw = http.post()
                .uri("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey())
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body(LogFormat.toJson(body))
                .retrieve()
                .body(String.class);
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : readTree(raw).path("content")) {
            if ("text".equals(block.path("type").asText())) {
                sb.append(block.path("text").asText());
            }
        }
        return sb.toString();
    }

    private String callOpenAi(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        // gpt-5.x 는 max_tokens 를 거부한다. max_completion_tokens 는 gpt-4o 등 이전 모델도 받는다.
        body.put("max_completion_tokens", props.maxTokens());
        body.put("temperature", ExperimentConstants.TEMPERATURE);
        String effort = reasoningEffort();
        if (effort != null) {
            body.put("reasoning_effort", effort);
        }
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        String raw = http.post()
                .uri("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(LogFormat.toJson(body))
                .retrieve()
                .body(String.class);
        return readTree(raw).path("choices").path(0).path("message").path("content").asText();
    }

    private static JsonNode readTree(String raw) {
        try {
            return MAPPER.readTree(raw == null ? "{}" : raw);
        } catch (Exception e) {
            throw new IllegalStateException("AI 응답을 해석할 수 없습니다: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // mock 응답 — 받은 로그 내용만 보고 규칙적으로 요약한다. (진짜 분석이 아님)
    // 받은 로그가 달라지면 응답도 달라지므로, 데모에서 필터 차이를 체감하는 용도로만 쓴다.
    // ------------------------------------------------------------------

    private static final Set<String> ERROR_LEVELS = Set.of("ERROR", "FATAL", "CRITICAL");
    private static final Pattern MASK_MARK = Pattern.compile("<([A-Z_]+)>");

    static String mockAnswer(List<Map<String, Object>> logs) {
        List<Map<String, Object>> flats = new ArrayList<>();
        logs.forEach(r -> flats.add(LogFormat.flatten(r)));
        flats.sort(Comparator.comparing(f -> String.valueOf(f.getOrDefault("logRecord.timeUnixNano", ""))));

        List<Map<String, Object>> errors = flats.stream().filter(AiClient::isError).toList();
        List<String> lines = new ArrayList<>(List.of(
                "[MOCK 응답 — 실제 AI 호출이 아닙니다. API 키를 설정하면 실제 응답으로 바뀝니다.]", ""));
        if (errors.isEmpty()) {
            lines.add("ERROR 수준 로그가 보이지 않아 근본 원인을 특정하기 어렵습니다.");
            return String.join("\n", lines);
        }

        Map<String, Object> first = errors.get(0);
        String svc = String.valueOf(first.getOrDefault("resource.service.name", "(서비스명 없음)"));
        String body = String.valueOf(first.getOrDefault("logRecord.body", ""));
        body = body.length() > 160 ? body.substring(0, 160) : body;
        Object trace = first.get("logRecord.traceId");
        Set<String> sameTrace = new TreeSet<>();
        for (Map<String, Object> f : errors) {
            if (trace != null && !"".equals(trace) && trace.equals(f.get("logRecord.traceId"))) {
                sameTrace.add(String.valueOf(f.getOrDefault("resource.service.name", "?")));
            }
        }

        // 기능 플래그 단서 탐색 (필드가 남아 있어야만 보인다)
        String flagKey = null;
        Object flagVariant = null;
        outer:
        for (Map<String, Object> f : flats) {
            for (Map.Entry<String, Object> e : f.entrySet()) {
                if (e.getKey().contains("feature_flag.key") && e.getValue() != null && !"".equals(e.getValue())) {
                    flagKey = String.valueOf(e.getValue());
                    flagVariant = f.get("logRecord.attributes.feature_flag.variant");
                    break outer;
                }
            }
        }

        if (flagKey != null) {
            lines.add("근본 원인: 기능 플래그 '" + flagKey + "'(variant=" + flagVariant + ")가 켜진 상태에서 "
                    + svc + " 서비스가 결제를 의도적으로 거부하고 있습니다. 토큰 자체의 문제가 아니라 장애 주입 플래그가 원인입니다.");
        } else {
            lines.add("근본 원인(추정): " + svc + " 서비스에서 처음 발생한 오류가 원인으로 보입니다.");
        }
        lines.add("");
        lines.add("근거:");
        lines.add("- 가장 먼저 발생한 ERROR: [" + svc + "] " + body);
        if (sameTrace.size() > 1) {
            lines.add("- 같은 traceId(" + trace + ")로 " + String.join(", ", sameTrace) + " 서비스에 오류가 연쇄 전파되었습니다.");
        } else if (trace == null || "".equals(trace)) {
            lines.add("- traceId가 없어 서비스 간 전파 관계는 확인할 수 없습니다.");
        }
        if (flagKey == null) {
            lines.add("- 오류 메시지 외에 원인을 설명하는 설정 변경 단서는 로그에서 찾지 못했습니다.");
        }
        Set<String> masked = new TreeSet<>();
        Matcher m = MASK_MARK.matcher(LogFormat.serialize(logs));
        while (m.find()) {
            masked.add(m.group(1));
        }
        if (!masked.isEmpty()) {
            lines.add("- 참고: 일부 값이 마스킹되어 있습니다(" + String.join(", ", masked) + ").");
        }
        return String.join("\n", lines);
    }

    private static boolean isError(Map<String, Object> f) {
        String sev = String.valueOf(f.getOrDefault("logRecord.severityText", "")).toUpperCase();
        Object num = f.get("logRecord.severityNumber");
        return ERROR_LEVELS.contains(sev) || (num instanceof Number n && n.intValue() >= 17);
    }
}
