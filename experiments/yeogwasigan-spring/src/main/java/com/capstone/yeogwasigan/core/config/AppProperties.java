package com.capstone.yeogwasigan.core.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 {@code yeogwasigan.*} 설정.
 *
 * <p>질문·temperature 처럼 실험 조건을 좌우하는 값은 여기가 아니라 {@link ExperimentConstants} 에 고정되어 있다.
 */
@ConfigurationProperties(prefix = "yeogwasigan")
public record AppProperties(
        String scenariosDir,
        String resultsDir,
        Presidio presidio,
        Ai ai,
        Experiment experiment,
        Blind blind) {

    public AppProperties {
        scenariosDir = blankTo(scenariosDir, "scenarios");
        resultsDir = blankTo(resultsDir, "results");
        presidio = presidio != null ? presidio : new Presidio(null, null, null, null, null);
        ai = ai != null ? ai : new Ai(null, null, 0, null, false, null, null);
        experiment = experiment != null ? experiment : new Experiment(0, null, null, null);
        blind = blind != null ? blind : new Blind(null, null, null, true);
    }

    /** Presidio 컨테이너 연결 설정. */
    public record Presidio(
            String analyzerUrl,
            String anonymizerUrl,
            String language,
            Duration timeout,
            List<String> skipFields) {

        public Presidio {
            analyzerUrl = blankTo(analyzerUrl, "http://localhost:5002");
            anonymizerUrl = blankTo(anonymizerUrl, "http://localhost:5001");
            language = blankTo(language, "en");
            timeout = timeout != null ? timeout : Duration.ofSeconds(30);
            skipFields = skipFields != null ? List.copyOf(skipFields) : List.of();
        }
    }

    /** 외부 AI 설정. temperature 는 없음(0 고정). */
    public record Ai(
            String provider,
            String model,
            int maxTokens,
            Duration timeout,
            boolean mock,
            String anthropicApiKey,
            String openaiApiKey) {

        public Ai {
            provider = blankTo(provider, "anthropic").toLowerCase();
            maxTokens = maxTokens > 0 ? maxTokens : 1024;
            timeout = timeout != null ? timeout : Duration.ofSeconds(120);
        }
    }

    /** 실험 일괄 실행 설정 (experiment 프로파일). */
    public record Experiment(
            int runs,
            List<String> scenarios,
            List<String> filters,
            Duration presidioWait) {

        public Experiment {
            runs = runs > 0 ? runs : 3;
            scenarios = scenarios != null ? List.copyOf(scenarios) : List.of();
            filters = filters != null && !filters.isEmpty()
                    ? filters.stream().map(String::toUpperCase).toList()
                    : ExperimentConstants.FILTER_NAMES;
            presidioWait = presidioWait != null ? presidioWait : Duration.ofSeconds(120);
        }
    }

    /** 블라인드 채점 설정 (blind 프로파일). */
    public record Blind(String action, String results, Long seed, Boolean normalize) {

        public Blind {
            action = blankTo(action, "prepare").toLowerCase();
            normalize = normalize == null || normalize;
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
