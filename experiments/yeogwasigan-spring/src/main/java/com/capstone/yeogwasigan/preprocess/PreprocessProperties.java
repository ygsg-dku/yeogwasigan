package com.capstone.yeogwasigan.preprocess;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application-preprocess.yml 의 {@code yeogwasigan.preprocess.*} 설정.
 *
 * <p>전처리는 세 필터(그대로/빼기/담기)보다 먼저, 세 조건에 똑같이 적용된다.
 * 규칙을 바꾸면 모든 조건의 입력이 함께 바뀌므로, 실험 시작 전에 정하고 끝까지 유지할 것.
 *
 * @param sourceDir         원본 수집 로그 폴더 (시나리오별 하위 폴더에 raw.jsonl). 원본은 절대 수정하지 않는다.
 * @param scenarios         전처리할 시나리오 폴더 이름
 * @param excludeServices   ① 관측 대상이 아닌 실험 장비 (resource.service.name 기준으로 레코드 제거)
 * @param excludeAttributePrefixes ① 레코드에서 지울 logRecord 속성 키 접두어 (장애 스위치 흔적)
 * @param excludeBodyContains      ① body 에 이 문자열이 있으면 레코드 제거 (예: 프록시의 flagd 호출)
 * @param stripPhrases      ③ 장애 자체인 에러 메시지에서 문구만 지운다 (PROTOCOL v1.3 "문구만 지운다"). drop 판정보다 먼저 적용
 * @param dropIfContains    ③ logRecord 의 body·속성(키 또는 값)에 이 문자열이 있으면 레코드를 통째로 뺀다
 *                          (PROTOCOL v1.3 "통째로 뺀다": 스위치 이름·feature flag·FeatureFlag·feature_flag). 대소문자 구분
 * @param keepFirst         ② 같은 묶음에서 시간순 처음 몇 건을 남길지
 * @param keepLast          ② 같은 묶음에서 시간순 마지막 몇 건을 남길지 (장애 지속 구간 보존)
 * @param keepSlowest       ② 응답시간을 알 수 있는 묶음에서 가장 느린 몇 건을 추가로 남길지 (지연 장애 증거 보존)
 * @param piiKeys           ④ 민감 키 (PROTOCOL v1.1 "민감 키 정의"). 원문(span·로그)에서 이 키의 값을 모은다. 비우면 ④ 생략
 * @param piiMinLength      ④ 이 길이 이상인 값만 센다·넣는다 (PROTOCOL v1.1: 6자 이상)
 * @param piiMaxPerKey      ④ 키마다 로그에 새로 넣을 값의 최대 개수 (시나리오 간 개수 차이를 줄인다)
 * @param maxInputTokens    ⑤ '그대로' 조건 기준 추정 토큰 상한. 넘으면 실패 처리(몰래 자르지 않는다)
 */
@ConfigurationProperties(prefix = "yeogwasigan.preprocess")
public record PreprocessProperties(
        String sourceDir,
        List<String> scenarios,
        List<String> excludeServices,
        List<String> excludeAttributePrefixes,
        List<String> excludeBodyContains,
        List<String> stripPhrases,
        List<String> dropIfContains,
        int keepFirst,
        int keepLast,
        int keepSlowest,
        List<String> piiKeys,
        int piiMinLength,
        int piiMaxPerKey,
        int maxInputTokens) {

    public PreprocessProperties {
        sourceDir = sourceDir == null || sourceDir.isBlank() ? "../../scenarios" : sourceDir.trim();
        scenarios = scenarios != null ? List.copyOf(scenarios) : List.of();
        excludeServices = excludeServices != null ? List.copyOf(excludeServices) : List.of();
        excludeAttributePrefixes = excludeAttributePrefixes != null ? List.copyOf(excludeAttributePrefixes) : List.of();
        excludeBodyContains = excludeBodyContains != null ? List.copyOf(excludeBodyContains) : List.of();
        stripPhrases = stripPhrases != null ? List.copyOf(stripPhrases) : List.of();
        dropIfContains = dropIfContains != null ? List.copyOf(dropIfContains) : List.of();
        keepFirst = keepFirst > 0 ? keepFirst : 10;
        keepLast = Math.max(keepLast, 0);
        keepSlowest = Math.max(keepSlowest, 0);
        piiKeys = piiKeys != null ? List.copyOf(piiKeys) : List.of();
        piiMinLength = piiMinLength > 0 ? piiMinLength : 6;
        piiMaxPerKey = Math.max(piiMaxPerKey, 0);
        maxInputTokens = maxInputTokens > 0 ? maxInputTokens : 250_000;
    }
}
