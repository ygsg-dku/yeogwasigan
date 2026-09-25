package com.capstone.yeogwasigan.preprocess;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 공통 전처리 일괄 실행 (preprocess 프로파일).
 *
 * <pre>
 * 입력: {sourceDir}/&lt;시나리오&gt;/raw.jsonl        (OTel Collector 원본. 절대 수정하지 않는다)
 * 출력: {scenariosDir}/&lt;시나리오&gt;/raw.jsonl      (내부 3층 형식, 실험·데모가 읽는 파일)
 *       {scenariosDir}/&lt;시나리오&gt;/preprocess_report.json   (단계별 제거 통계 + 적용 규칙)
 * </pre>
 *
 * '그대로' 기준 추정 토큰이 상한을 넘는 시나리오는 raw.jsonl 을 쓰지 않고 실패로 보고한다. (몰래 자르지 않는다)
 */
@Component
@Profile("preprocess")
public class PreprocessRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PreprocessRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final PreprocessProperties props;
    private final AppProperties appProps;

    public PreprocessRunner(PreprocessProperties props, AppProperties appProps) {
        this.props = props;
        this.appProps = appProps;
    }

    @Override
    public void run(String... args) throws Exception {
        if (props.scenarios().isEmpty()) {
            throw new IllegalStateException("yeogwasigan.preprocess.scenarios 가 비어 있습니다.");
        }
        Path source = Path.of(props.sourceDir()).toAbsolutePath().normalize();
        Path target = Path.of(appProps.scenariosDir()).toAbsolutePath().normalize();
        if (source.equals(target)) {
            throw new IllegalStateException("원본 폴더와 출력 폴더가 같습니다. 원본을 덮어쓰지 않도록 다르게 지정하세요: " + source);
        }
        Preprocessor pre = new Preprocessor(props);
        List<String> failed = new ArrayList<>();

        for (String id : props.scenarios()) {
            Path in = source.resolve(id).resolve("raw.jsonl");
            log.info("[{}] 읽는 중: {}", id, in);
            Preprocessor.Result r = pre.apply(LogFormat.parse(Files.readString(in, StandardCharsets.UTF_8)));

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("scenario", id);
            report.put("source", in.toString());
            report.put("rules", props);
            report.putAll(r.report());

            Path outDir = target.resolve(id);
            Files.createDirectories(outDir);
            JSON.writeValue(outDir.resolve("preprocess_report.json").toFile(), report);

            @SuppressWarnings("unchecked")
            Map<String, Object> size = (Map<String, Object>) r.report().get("step5_size");
            int tokens = (int) size.get("estimatedTokensPassthrough");
            if (tokens > props.maxInputTokens()) {
                Files.deleteIfExists(outDir.resolve("raw.jsonl"));   // 이전 실행의 결과가 남아 있으면 실험에 섞이므로 지운다
                failed.add(id);
                log.error("[{}] 실패: '그대로' 추정 {} 토큰 > 상한 {}. keep-first 등을 줄여서 다시 실행하세요.",
                        id, tokens, props.maxInputTokens());
                continue;
            }
            Files.writeString(outDir.resolve("raw.jsonl"), LogFormat.serialize(r.records()) + "\n", StandardCharsets.UTF_8);
            log.info("[{}] {}건 → {}건, 필드 {}개, '그대로' 추정 {} 토큰", id, r.report().get("inputRecords"),
                    size.get("outputRecords"), size.get("distinctFields"), tokens);
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("토큰 상한을 넘은 시나리오: " + failed);
        }
        log.info("완료: {}", target);
    }
}
