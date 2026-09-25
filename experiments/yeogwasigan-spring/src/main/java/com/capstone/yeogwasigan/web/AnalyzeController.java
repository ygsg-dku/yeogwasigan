package com.capstone.yeogwasigan.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.yeogwasigan.core.ai.AiAnswer;
import com.capstone.yeogwasigan.core.ai.AiClient;
import com.capstone.yeogwasigan.core.config.ExperimentConstants;
import com.capstone.yeogwasigan.core.filter.FilterRegistry;
import com.capstone.yeogwasigan.core.filter.FilterResult;
import com.capstone.yeogwasigan.core.filter.LogFilter;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.presidio.PresidioClient;
import com.capstone.yeogwasigan.core.scenario.PiiItem;
import com.capstone.yeogwasigan.core.scenario.ScenarioRepository;
import com.capstone.yeogwasigan.core.scoring.ResidualScorer;
import com.capstone.yeogwasigan.core.template.TemplateLoader;
import com.capstone.yeogwasigan.preprocess.PreprocessProperties;
import com.capstone.yeogwasigan.preprocess.Preprocessor;
import com.capstone.yeogwasigan.preprocess.SpanPiiExtractor;
import com.capstone.yeogwasigan.web.dto.AnalyzeRequest;
import com.capstone.yeogwasigan.web.dto.AnalyzeResponse;
import com.capstone.yeogwasigan.web.dto.CompareRow;

/**
 * 데모 웹 API. 필터·채점·AI 호출은 전부 core 를 그대로 호출한다. (실험 러너와 같은 코드 경로)
 *
 * <pre>
 * POST /api/analyze   { log, purpose, filter, caseId? } → 필터 결과 + AI 응답
 * POST /api/filter    (같은 입력) → AI 호출 없이 필터 결과만 — 방식 전환 시 즉시 미리보기
 * POST /api/compare   (같은 입력) → 세 방식의 필드 수·잔존 수 요약
 * GET  /api/meta      목적·필터 목록, 고정 질문, AI 모드, 샘플 목록, Presidio 상태
 * GET  /api/sample/{scenarioId}   샘플 원본 로그
 * </pre>
 *
 * 요청의 preprocess=true 면 필터 전에 실험과 같은 공통 전처리({@link Preprocessor})를 적용한다.
 * 원본 수집 로그(수 MB~수십 MB)를 그대로 올려 볼 때 쓴다.
 *
 * 민감값 잔존 수 기준: 데모에서는 사용자가 로그를 직접 고칠 수 있으므로,
 * scenarios/*&#47;injected_pii.yaml 에 등록된 민감값 전체(합집합)를 기준으로 단순 문자열 검색한다.
 */
@RestController
@RequestMapping("/api")
public class AnalyzeController {

    private final FilterRegistry filters;
    private final ResidualScorer scorer;
    private final AiClient aiClient;
    private final ScenarioRepository scenarios;
    private final TemplateLoader templates;
    private final PresidioClient presidio;
    private final PreprocessProperties preprocessProps;

    public AnalyzeController(FilterRegistry filters, ResidualScorer scorer, AiClient aiClient,
                             ScenarioRepository scenarios, TemplateLoader templates, PresidioClient presidio,
                             PreprocessProperties preprocessProps) {
        this.filters = filters;
        this.scorer = scorer;
        this.aiClient = aiClient;
        this.scenarios = scenarios;
        this.templates = templates;
        this.presidio = presidio;
        this.preprocessProps = preprocessProps;
    }

    @PostMapping("/analyze")
    public AnalyzeResponse analyze(@RequestBody AnalyzeRequest req) {
        AnalyzeResponse filtered = runFilter(req);
        // 한도를 넘는 요청은 AI 를 부르지 않고 바로 알려준다 (비용·429 오류 방지)
        int tokens = Preprocessor.estimateTokens(AiClient.buildPrompt(filtered.output(), ExperimentConstants.FIXED_QUESTION));
        if (tokens > preprocessProps.maxInputTokens()) {
            throw new IllegalArgumentException(String.format(
                    "로그가 너무 큽니다: 약 %,d 토큰 (한도 %,d). %s", tokens, preprocessProps.maxInputTokens(),
                    req.preprocess() ? "공통 전처리를 거쳐도 넘습니다. 장애 구간만 잘라서 넣어 주세요."
                            : "[공통 전처리]를 켜거나 장애 구간만 잘라서 넣어 주세요."));
        }
        // "실제로 나간 것"(output)만 AI 로 보낸다. 질문은 세 방식 모두 고정 질문.
        AiAnswer answer = aiClient.ask(filtered.output(), ExperimentConstants.FIXED_QUESTION);
        return filtered.withAi(answer.text(), answer.mode(), answer.model());
    }

    @PostMapping("/filter")
    public AnalyzeResponse filterOnly(@RequestBody AnalyzeRequest req) {
        return runFilter(req);
    }

    @PostMapping("/compare")
    public Map<String, Object> compare(@RequestBody AnalyzeRequest req) {
        List<CompareRow> rows = new ArrayList<>();
        for (LogFilter f : filters.all()) {
            try {
                AnalyzeResponse r = runFilter(req.withFilter(f.name()));
                rows.add(new CompareRow(f.name(), f.label(), r.fieldsBefore(), r.fieldsAfter(), r.residualCount(), null));
            } catch (RuntimeException e) {
                // 예: Presidio 가 아직 준비되지 않았을 때 빼기 줄만 오류로 표시하고 나머지는 보여준다
                rows.add(new CompareRow(f.name(), f.label(), null, null, null, e.getMessage()));
            }
        }
        return Map.of("rows", rows);
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("purposes", templates.list().stream()
                .map(t -> Map.of("id", t.purposeId(), "name", t.displayName(), "description", t.description()))
                .toList());
        m.put("filters", filters.all().stream().map(f -> Map.of("id", f.name(), "label", f.label())).toList());
        m.put("question", ExperimentConstants.FIXED_QUESTION);
        m.put("aiMode", aiClient.mode());
        m.put("aiModel", aiClient.model());
        m.put("samples", scenarios.ids());
        m.put("presidioUp", presidio.isUp());
        return m;
    }

    @GetMapping("/sample/{scenarioId}")
    public Map<String, Object> sample(@PathVariable String scenarioId) {
        return Map.of("scenarioId", scenarioId, "log", scenarios.rawText(scenarioId));
    }

    private AnalyzeResponse runFilter(AnalyzeRequest req) {
        templates.get(req.purpose());                       // 알 수 없는 목적이면 400
        LogFilter filter = filters.get(req.filter());       // 알 수 없는 필터면 400
        List<Map<String, Object>> logs = LogFormat.parse(req.log());
        Map<String, Object> preprocessSummary = null;
        List<PiiItem> piiForScoring = scenarios.allPii();
        if (req.preprocess()) {
            // 실험(preprocess 프로파일)과 같은 Preprocessor·같은 규칙
            Preprocessor.Result pre = new Preprocessor(preprocessProps).apply(logs,
                    SpanPiiExtractor.extract(req.log(), new HashSet<>(preprocessProps.piiKeys())));
            logs = pre.records();
            preprocessSummary = new LinkedHashMap<>();
            preprocessSummary.put("inputRecords", pre.report().get("inputRecords"));
            preprocessSummary.put("outputRecords", logs.size());
            preprocessSummary.put("pii", pre.pii().size());
            piiForScoring = new ArrayList<>(scenarios.allPii());
            piiForScoring.addAll(pre.pii());   // 올린 파일에서 새로 넣은 값도 잔존 수에 포함
        }
        FilterResult result = filter.apply(logs, req.caseId(), req.purpose());
        Map<String, Object> notes = result.notes();
        if (preprocessSummary != null) {
            notes = new LinkedHashMap<>(notes);
            notes.put("preprocess", preprocessSummary);
        }
        ResidualScorer.ResidualResult residual = scorer.score(result.output(), piiForScoring);
        return new AnalyzeResponse(
                filter.name(),
                filter.label(),
                result.output(),
                result.fieldsBefore(),
                result.fieldsAfter(),
                result.droppedFields(),
                residual.total(),
                residual.detail(),
                notes,
                null, null, null);
    }
}
