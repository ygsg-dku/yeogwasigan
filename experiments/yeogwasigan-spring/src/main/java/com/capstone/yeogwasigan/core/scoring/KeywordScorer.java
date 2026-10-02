package com.capstone.yeogwasigan.core.scoring;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 키워드 자동 채점 — 팀 PROTOCOL 의 "채점 2단계"(서비스 적중 / 원인 적중).
 *
 * <p>판정 키워드는 D 가 장애 로그를 보기 전에 쓴 {@code scenarios.json} 을 그대로 읽는다. 여기서 고치지 않는다.
 * AI 답에 키워드(한국어·영어 목록 합집합)가 하나라도 들어 있으면 적중이다. 대소문자는 무시한다.
 *
 * <p>scenarios.json 의 시나리오 이름은 실제 폴더 이름과 조금 다르다(S3_productCatalogLocked ↔ S3_productCatalogLockContention).
 * 그래서 첫 "_" 앞의 접두어(S1, S2, …)로 연결한다.
 *
 * <p>단순 문자열 검색이라 "payment 문제는 아닙니다" 같은 부정문도 적중으로 센다. 참고용 1차 판정이고,
 * 최종 판정은 블라인드 사람 채점으로 한다.
 */
@Component
public class KeywordScorer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** scenarios.json 의 시나리오 하나. */
    public record Rule(String id, String key, String description, List<String> serviceKeywords, List<String> causeKeywords) {
    }

    /** 답 하나의 채점 결과. matched 는 답에서 실제로 찾은 키워드. */
    public record Score(String ruleId, boolean serviceHit, boolean causeHit,
                        List<String> serviceMatched, List<String> causeMatched) {
    }

    private final Path source;
    private final Map<String, Rule> rules;   // 접두어(S1…) → 규칙
    private final Map<String, Object> meta;

    @Autowired   // 생성자가 둘이라 Spring 이 쓸 쪽을 지정한다 (Path 생성자는 테스트용)
    public KeywordScorer(@Value("${yeogwasigan.scoring.keywords-file:scoring/scenarios.json}") String path) {
        this(Path.of(path));
    }

    public KeywordScorer(Path source) {
        this.source = source.toAbsolutePath().normalize();
        this.rules = new LinkedHashMap<>();
        this.meta = new LinkedHashMap<>();
        if (!Files.isRegularFile(this.source)) {
            return;   // 채점 기준 파일이 없으면 채점하지 않는다 (화면에 "채점 기준 없음")
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(this.source.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("채점 기준 파일을 읽을 수 없습니다: " + this.source, e);
        }
        root.fields().forEachRemaining(e -> {
            if ("_meta".equals(e.getKey())) {
                meta.putAll(MAPPER.convertValue(e.getValue(), new TypeReference<Map<String, Object>>() {
                }));
                return;
            }
            JsonNode v = e.getValue();
            Rule rule = new Rule(prefix(e.getKey()), e.getKey(), v.path("description").asText(""),
                    keywords(v.path("service_hit_keywords")), keywords(v.path("cause_hit_keywords")));
            rules.put(rule.id(), rule);
        });
    }

    public boolean available() {
        return !rules.isEmpty();
    }

    public Path source() {
        return source;
    }

    public Map<String, Object> meta() {
        return meta;
    }

    public List<Rule> rules() {
        return List.copyOf(rules.values());
    }

    /** 시나리오 ID(폴더 이름 또는 S1 같은 접두어)에 맞는 규칙. */
    public Optional<Rule> rule(String scenarioId) {
        return scenarioId == null ? Optional.empty() : Optional.ofNullable(rules.get(prefix(scenarioId)));
    }

    /** 채점. 이 시나리오의 규칙이 없으면 빈 값. */
    public Optional<Score> score(String scenarioId, String answer) {
        return rule(scenarioId).map(r -> {
            String text = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
            List<String> service = matched(text, r.serviceKeywords());
            List<String> cause = matched(text, r.causeKeywords());
            return new Score(r.id(), !service.isEmpty(), !cause.isEmpty(), service, cause);
        });
    }

    static String prefix(String scenarioId) {
        int i = scenarioId.indexOf('_');
        return i < 0 ? scenarioId : scenarioId.substring(0, i);
    }

    private static List<String> matched(String lowerText, List<String> keywords) {
        List<String> out = new ArrayList<>();
        for (String k : keywords) {
            if (!k.isBlank() && lowerText.contains(k.toLowerCase(Locale.ROOT))) {
                out.add(k);
            }
        }
        return out;
    }

    /** {"ko": [...], "en": [...]} → 합집합 (순서 유지, 중복 제거). */
    private static List<String> keywords(JsonNode byLang) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        byLang.fields().forEachRemaining(e -> e.getValue().forEach(k -> out.add(k.asText())));
        return List.copyOf(out);
    }
}
