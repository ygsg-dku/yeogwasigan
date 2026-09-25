package com.capstone.yeogwasigan.core.presidio;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.capstone.yeogwasigan.core.config.AppProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Microsoft Presidio 공식 Docker 이미지(analyzer / anonymizer)를 HTTP 로 호출한다.
 *
 * <h3>원칙: 기본 설정 그대로</h3>
 * <ul>
 *   <li>analyzer 에는 {@code text} 와 {@code language} 만 보낸다. entities, score_threshold,
 *       ad_hoc_recognizers, allow_list 등은 전혀 지정하지 않는다 → 기본 인식기 전체 + 기본 임계값.</li>
 *   <li>anonymizer 에는 {@code anonymizers} 를 보내지 않는다 → 기본 연산자(replace → "&lt;ENTITY_TYPE&gt;").</li>
 *   <li>한국어 이름·국내 계좌번호를 못 잡아도 커스텀 인식기를 추가하지 않는다.
 *       그게 빼기 방식의 실제 한계이고, 실험에서 측정할 대상이다.</li>
 * </ul>
 *
 * <p>호출 흐름: {@code POST {analyzer}/analyze} → 탐지 결과를 그대로 {@code POST {anonymizer}/anonymize} 에 넘겨 치환.
 */
@Component
public class PresidioClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient analyzer;
    private final RestClient anonymizer;
    private final String language;
    private final String analyzerUrl;
    private final String anonymizerUrl;

    public PresidioClient(AppProperties props) {
        AppProperties.Presidio p = props.presidio();
        this.analyzerUrl = p.analyzerUrl();
        this.anonymizerUrl = p.anonymizerUrl();
        this.language = p.language();
        this.analyzer = restClient(p.analyzerUrl(), p.timeout());
        this.anonymizer = restClient(p.anonymizerUrl(), p.timeout());
    }

    private static RestClient restClient(String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 탐지 결과 1건. start/end 는 자바 문자열 인덱스(UTF-16)로 변환된 값. */
    public record Detection(String entityType, int start, int end, double score, String original) {
    }

    /** 치환 결과. text = 마스킹된 텍스트, items = anonymizer 가 실제로 치환한 구간(겹침 해소 후). */
    public record Anonymized(String text, List<Detection> detections, List<String> replacedEntityTypes) {
    }

    /**
     * 텍스트 하나를 analyzer → anonymizer 순서로 처리한다. 탐지가 없으면 anonymizer 는 호출하지 않는다.
     * 빈 문자열은 Presidio REST 가 "No text provided" 오류를 내므로 호출 없이 그대로 돌려준다.
     */
    public Anonymized scrub(String text) {
        if (text == null || text.isEmpty()) {
            return new Anonymized(text, List.of(), List.of());
        }
        JsonNode results = analyzeRaw(text);
        if (results.isEmpty()) {
            return new Anonymized(text, List.of(), List.of());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("analyzer_results", results);   // analyzer 응답을 가공 없이 그대로 전달
        JsonNode resp = post(anonymizer, "/anonymize", body, anonymizerUrl);

        List<String> replaced = new ArrayList<>();
        resp.path("items").forEach(item -> replaced.add(item.path("entity_type").asText()));
        return new Anonymized(resp.path("text").asText(), toDetections(text, results), replaced);
    }

    /** analyzer 만 호출한다. ([C] 담기의 innerScanEngine=presidio 에서 재사용) */
    public List<Detection> analyze(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return toDetections(text, analyzeRaw(text));
    }

    private JsonNode analyzeRaw(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("language", language);
        JsonNode resp = post(analyzer, "/analyze", body, analyzerUrl);
        if (!resp.isArray()) {
            throw new PresidioUnavailableException("Presidio analyzer 응답 형식이 예상과 다릅니다: " + resp, null);
        }
        return resp;
    }

    /**
     * Presidio(파이썬)의 start/end 는 코드포인트 단위다. 자바 String 인덱스(UTF-16)로 바꿔서 원문 구간을 잘라낸다.
     * (한글은 둘이 같지만, 이모지 같은 보충 문자가 섞이면 달라진다)
     */
    private static List<Detection> toDetections(String text, JsonNode results) {
        List<Detection> out = new ArrayList<>();
        int cpCount = text.codePointCount(0, text.length());
        for (JsonNode r : results) {
            int cs = Math.max(0, Math.min(cpCount, r.path("start").asInt()));
            int ce = Math.max(cs, Math.min(cpCount, r.path("end").asInt()));
            int s = text.offsetByCodePoints(0, cs);
            int e = text.offsetByCodePoints(0, ce);
            out.add(new Detection(r.path("entity_type").asText(), s, e, r.path("score").asDouble(), text.substring(s, e)));
        }
        return out;
    }

    /** analyzer 가 응답하는지 확인한다. */
    public boolean isAnalyzerUp() {
        return ping(analyzer);
    }

    public boolean isAnonymizerUp() {
        return ping(anonymizer);
    }

    public boolean isUp() {
        return isAnalyzerUp() && isAnonymizerUp();
    }

    private static boolean ping(RestClient client) {
        try {
            client.get().uri("/health").retrieve().body(String.class);
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    /**
     * Presidio 가 준비될 때까지 기다린다. (analyzer 는 시작할 때 spaCy 모델을 불러오느라 수십 초 걸린다)
     *
     * @return 제한 시간 안에 준비되면 true
     */
    public boolean awaitReady(Duration maxWait) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (true) {
            if (isUp()) {
                return true;
            }
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** analyzer 가 지원하는 엔티티 목록. run_meta.json 에 기록해서 Presidio 버전 차이를 추적한다. */
    public List<String> supportedEntities() {
        try {
            String raw = analyzer.get().uri("/supportedentities?language=" + language).retrieve().body(String.class);
            List<String> out = new ArrayList<>();
            MAPPER.readTree(raw).forEach(n -> out.add(n.asText()));
            out.sort(null);
            return out;
        } catch (RestClientException | JsonProcessingException e) {
            return List.of();
        }
    }

    public String analyzerUrl() {
        return analyzerUrl;
    }

    public String anonymizerUrl() {
        return anonymizerUrl;
    }

    public String language() {
        return language;
    }

    private static JsonNode post(RestClient client, String path, Object body, String baseUrl) {
        try {
            String json = MAPPER.writeValueAsString(body);
            String raw = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .body(String.class);
            return MAPPER.readTree(raw == null ? "null" : raw);
        } catch (RestClientException e) {
            throw new PresidioUnavailableException(
                    "Presidio(" + baseUrl + path + ")를 호출할 수 없습니다. "
                            + "`docker compose up -d presidio-analyzer presidio-anonymizer` 로 컨테이너를 띄웠는지, "
                            + "분석기 모델 로딩(30초~1분)이 끝났는지 확인하세요. 원인: " + e.getMessage(), e);
        } catch (JsonProcessingException e) {
            throw new PresidioUnavailableException("Presidio 응답을 해석할 수 없습니다: " + e.getOriginalMessage(), e);
        }
    }
}
