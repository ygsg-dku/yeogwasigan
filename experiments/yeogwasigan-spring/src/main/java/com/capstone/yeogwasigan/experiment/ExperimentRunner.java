package com.capstone.yeogwasigan.experiment;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.capstone.yeogwasigan.core.ai.AiAnswer;
import com.capstone.yeogwasigan.core.ai.AiClient;
import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.config.ExperimentConstants;
import com.capstone.yeogwasigan.core.filter.FilterRegistry;
import com.capstone.yeogwasigan.core.filter.FilterResult;
import com.capstone.yeogwasigan.core.filter.LogFilter;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.presidio.PresidioClient;
import com.capstone.yeogwasigan.core.scenario.Scenario;
import com.capstone.yeogwasigan.core.scenario.ScenarioRepository;
import com.capstone.yeogwasigan.core.scoring.ResidualScorer;
import com.capstone.yeogwasigan.core.template.TemplateLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 실험 일괄 실행 (experiment 프로파일).
 *
 * <pre>
 * for (Scenario s : scenarios)
 *   for (String filter : List.of("PASSTHROUGH", "DENYLIST", "ALLOWLIST"))
 *     for (int run = 1; run &lt;= 3; run++) {      // 3회 반복으로 AI 응답 흔들림 보정
 *       FilterResult r = filters.get(filter).apply(s.raw(), s.id());
 *       int residual = scorer.count(r.output(), s.injectedPii());
 *       AiAnswer answer = aiClient.ask(r.output(), FIXED_QUESTION);
 *       csv.write(...);
 *     }
 * </pre>
 *
 * 결과 (results/&lt;실행시각&gt;/)
 * <ul>
 *   <li>results.csv — scenario_id, filter, run_no, fields_before, fields_after, residual_pii_count, ai_answer, rca_score(빈칸)</li>
 *   <li>payloads/ — 조건별로 AI 에 "실제로 나간" 로그 (JSONL). 재현·검증용</li>
 *   <li>run_meta.json — 질문, 모델, temperature, AI 모드(live/mock), 템플릿, Presidio 설정 등 실험 조건 기록</li>
 * </ul>
 *
 * 실행 옵션 (--yeogwasigan.experiment.*):
 * runs=3, scenarios=s01_payment_fail,..., filters=PASSTHROUGH,DENYLIST,ALLOWLIST
 */
@Component
@Profile("experiment")
public class ExperimentRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ExperimentRunner.class);

    static final String[] CSV_HEADER = {
            "scenario_id", "filter", "run_no", "fields_before", "fields_after",
            "residual_pii_count", "ai_answer", "rca_score"};

    private final FilterRegistry filters;
    private final ResidualScorer scorer;
    private final AiClient aiClient;
    private final ScenarioRepository scenarios;
    private final TemplateLoader templates;
    private final PresidioClient presidio;
    private final AppProperties props;

    public ExperimentRunner(FilterRegistry filters, ResidualScorer scorer, AiClient aiClient,
                            ScenarioRepository scenarios, TemplateLoader templates, PresidioClient presidio,
                            AppProperties props) {
        this.filters = filters;
        this.scorer = scorer;
        this.aiClient = aiClient;
        this.scenarios = scenarios;
        this.templates = templates;
        this.presidio = presidio;
        this.props = props;
    }

    @Override
    public void run(String... args) throws Exception {
        Path csv = execute();
        log.info("완료: {}", csv);
        log.info("다음 단계(블라인드 채점 파일 생성): ./gradlew bootRun --args='--spring.profiles.active=blind "
                + "--yeogwasigan.blind.action=prepare --yeogwasigan.blind.results={}'", relative(csv));
    }

    /** 실험을 실행하고 results.csv 경로를 돌려준다. */
    public Path execute() throws IOException {
        AppProperties.Experiment exp = props.experiment();

        // 1) 대상 시나리오·필터 확정
        List<String> ids = exp.scenarios().isEmpty() ? scenarios.ids() : exp.scenarios();
        if (ids.isEmpty()) {
            throw new IllegalStateException("실행할 시나리오가 없습니다: " + scenarios.root());
        }
        List<Scenario> list = new ArrayList<>();
        for (String id : ids) {
            list.add(scenarios.get(id));
        }
        List<LogFilter> filterList = exp.filters().stream().map(filters::get).toList();

        // 2) Presidio 를 쓰는 조건이 있으면 준비부터 확인 (AI 호출 비용을 쓰기 전에 실패시킨다)
        //    - 빼기(DENYLIST)
        //    - 담기(ALLOWLIST) 인데 템플릿의 innerScanEngine 이 presidio 인 경우
        boolean needsPresidio = exp.filters().contains(ExperimentConstants.DENYLIST)
                || (exp.filters().contains(ExperimentConstants.ALLOWLIST)
                && templates.get(ExperimentConstants.PURPOSE_ID).innerScanEngine()
                == com.capstone.yeogwasigan.core.template.PurposeTemplate.InnerScanEngine.PRESIDIO);
        List<String> presidioEntities = List.of();
        if (needsPresidio) {
            log.info("Presidio 준비 확인 중: {} / {}", presidio.analyzerUrl(), presidio.anonymizerUrl());
            if (!presidio.awaitReady(exp.presidioWait())) {
                throw new IllegalStateException("Presidio 에 연결할 수 없습니다 (" + presidio.analyzerUrl() + ", "
                        + presidio.anonymizerUrl() + "). `docker compose up -d presidio-analyzer presidio-anonymizer` "
                        + "로 띄운 뒤 다시 실행하세요.");
            }
            presidioEntities = presidio.supportedEntities();
        }

        String mode = aiClient.mode();
        if (!"live".equals(mode)) {
            log.warn("⚠ mock 모드입니다. 이 결과는 파이프라인 점검용이며 실험 결과로 쓰면 안 됩니다.");
        }

        // 3) 결과 폴더
        LocalDateTime started = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Path runDir = Path.of(props.resultsDir()).toAbsolutePath()
                .resolve(started.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        Path payloadDir = runDir.resolve("payloads");
        Files.createDirectories(payloadDir);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("startedAt", started.toString());
        meta.put("purpose", ExperimentConstants.PURPOSE_ID);
        meta.put("question", ExperimentConstants.FIXED_QUESTION);
        meta.put("aiMode", mode);
        meta.put("aiProvider", aiClient.provider());
        meta.put("aiModel", aiClient.model());
        meta.put("temperature", ExperimentConstants.TEMPERATURE);
        meta.put("maxTokens", aiClient.maxTokens());
        meta.put("reasoningEffort", aiClient.reasoningEffort());
        meta.put("runs", exp.runs());
        meta.put("filters", exp.filters());
        meta.put("scenarios", ids);
        meta.put("template", templates.describe(ExperimentConstants.PURPOSE_ID));
        Map<String, Object> presidioMeta = new LinkedHashMap<>();
        presidioMeta.put("analyzerUrl", presidio.analyzerUrl());
        presidioMeta.put("anonymizerUrl", presidio.anonymizerUrl());
        presidioMeta.put("language", presidio.language());
        presidioMeta.put("skipFields", props.presidio().skipFields());
        presidioMeta.put("supportedEntities", presidioEntities);   // Presidio 버전이 바뀌면 이 목록이 달라질 수 있다
        meta.put("presidio", presidioMeta);

        // 4) 본 실험
        int total = list.size() * filterList.size() * exp.runs();
        int done = 0;
        Path csvPath = runDir.resolve("results.csv");
        try (BufferedWriter w = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8)) {
            w.write('﻿');   // 엑셀에서 한글이 깨지지 않도록 BOM
            try (CSVPrinter csv = new CSVPrinter(w, CSVFormat.DEFAULT.builder().setHeader(CSV_HEADER).build())) {
                for (Scenario s : list) {
                    for (LogFilter filter : filterList) {
                        for (int run = 1; run <= exp.runs(); run++) {
                            FilterResult r = filter.apply(s.raw(), s.id(), ExperimentConstants.PURPOSE_ID);
                            int residual = scorer.count(r.output(), s.injectedPii());
                            AiAnswer answer = aiClient.ask(r.output(), ExperimentConstants.FIXED_QUESTION);

                            Files.writeString(payloadDir.resolve(s.id() + "__" + filter.name() + "__run" + run + ".jsonl"),
                                    LogFormat.serialize(r.output()) + "\n", StandardCharsets.UTF_8);
                            csv.printRecord(s.id(), filter.name(), run, r.fieldsBefore(), r.fieldsAfter(),
                                    residual, answer.text(), "");
                            csv.flush();

                            done++;
                            log.info("[{}/{}] {} {} run{}  필드 {}→{}  잔존 {}  AI={}", done, total, s.id(),
                                    String.format("%-11s", filter.name()), run, r.fieldsBefore(), r.fieldsAfter(),
                                    residual, answer.mode());
                            if ("error".equals(answer.mode())) {
                                log.warn("    {}", answer.text());
                            }
                        }
                    }
                }
            }
        }

        meta.put("finishedAt", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(runDir.resolve("run_meta.json").toFile(), meta);
        return csvPath;
    }

    private static Path relative(Path p) {
        try {
            return Path.of("").toAbsolutePath().relativize(p);
        } catch (IllegalArgumentException e) {
            return p;
        }
    }
}
