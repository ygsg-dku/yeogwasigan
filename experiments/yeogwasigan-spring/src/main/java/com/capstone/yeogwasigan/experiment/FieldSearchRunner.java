package com.capstone.yeogwasigan.experiment;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.capstone.yeogwasigan.core.ai.AiAnswer;
import com.capstone.yeogwasigan.core.ai.AiClient;
import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.config.ExperimentConstants;
import com.capstone.yeogwasigan.core.filter.AllowlistFilter;
import com.capstone.yeogwasigan.core.filter.FilterResult;
import com.capstone.yeogwasigan.core.presidio.PresidioClient;
import com.capstone.yeogwasigan.core.scenario.Scenario;
import com.capstone.yeogwasigan.core.scenario.ScenarioRepository;
import com.capstone.yeogwasigan.core.scoring.ResidualScorer;
import com.capstone.yeogwasigan.core.template.PurposeTemplate;
import com.capstone.yeogwasigan.core.template.PurposeTemplate.FieldAction;
import com.capstone.yeogwasigan.core.template.TemplateLoader;
import com.capstone.yeogwasigan.core.tokenize.CaseScopedTokenizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 필드 선택 알고리즘의 AI 확인 단계 (fieldsearch 프로파일).
 *
 * <pre>
 *   python3 scripts/field_select.py --json data/fieldsearch_candidates.json   # 1차 후보 (AI 없음)
 *   ./gradlew bootRun --args='--spring.profiles.active=fieldsearch'          # 이 단계
 * </pre>
 *
 * 1) 기준 목록 = 문맥 칸(서비스·시각·심각도) + 1차에서 토큰화로 판정된 ID 칸
 * 2) 고르는 장애(select)에서 기준 목록과, 기준 + 후보 하나씩을 담기 필터로 거른 뒤 AI 에 묻고 채점한다
 * 3) 기준보다 점수가 오르는 후보만 남긴다 (오르지 않으면 넣을 이유가 없다 = 칸을 늘리지 않는다)
 * 4) 되돌려 빼기: 남긴 칸을 합친 목록에서 하나씩 빼 보고, 점수가 떨어지지 않으면 뺀다 (칸끼리의 영향을 잡는다)
 * 5) 최종 목록과 손으로 고른 목록을 검증 장애(validate, 고를 때 안 쓴 장애)에서 비교한다
 * resume-from=이전 summary.json 이면 1~3 을 건너뛰고 4 부터 한다
 *
 * 채점은 AI 판정(정답 원인 설명과 답을 비교, 0 틀림 / 1 서비스만 / 2 서비스+원인)이다. 사람 블라인드 채점으로 일부를 확인한다.
 * 결과: results/fieldsearch_시각/ steps.csv · summary.json · selected_template.yaml
 */
@Component
@Profile("fieldsearch")
public class FieldSearchRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FieldSearchRunner.class);
    private static final String PURPOSE = "FIELD_SEARCH";
    private static final Pattern DIGIT = Pattern.compile("[012]");

    private final ScenarioRepository scenarios;
    private final ResidualScorer scorer;
    private final AiClient ai;
    private final CaseScopedTokenizer tokenizer;
    private final PresidioClient presidio;
    private final AppProperties props;
    private final String candidatesFile;
    private final List<String> selectIds;
    private final List<String> validateIds;
    private final int maxCandidates;
    private final int minClue;
    private final String resumeFrom;
    private final Map<String, String> truths;
    private final ConfigurableApplicationContext context;

    public FieldSearchRunner(ConfigurableApplicationContext context, ScenarioRepository scenarios, ResidualScorer scorer, AiClient ai, CaseScopedTokenizer tokenizer,
                             PresidioClient presidio, AppProperties props,
                             @Value("${yeogwasigan.fieldsearch.candidates-file}") String candidatesFile,
                             @Value("${yeogwasigan.fieldsearch.select}") List<String> selectIds,
                             @Value("${yeogwasigan.fieldsearch.validate}") List<String> validateIds,
                             @Value("${yeogwasigan.fieldsearch.max-candidates:15}") int maxCandidates,
                             @Value("${yeogwasigan.fieldsearch.min-clue:0}") int minClue,
                             @Value("${yeogwasigan.fieldsearch.resume-from:}") String resumeFrom,
                             @Value("#{${yeogwasigan.fieldsearch.truths}}") Map<String, String> truths) {
        this.context = context;
        this.scenarios = scenarios;
        this.scorer = scorer;
        this.ai = ai;
        this.tokenizer = tokenizer;
        this.presidio = presidio;
        this.props = props;
        this.candidatesFile = candidatesFile;
        this.selectIds = selectIds;
        this.validateIds = validateIds;
        this.maxCandidates = maxCandidates;
        this.minClue = minClue;
        this.resumeFrom = resumeFrom;
        this.truths = truths;
    }

    /** 칸 목록 하나: 담을 칸과 토큰화할 칸, 본문 조건. */
    record FieldSet(String label, List<String> fields, Set<String> tokenize, boolean bodyOnlyWhenImportant) {
        FieldSet with(String label, String field) {
            List<String> f = new ArrayList<>(fields);
            if (!f.contains(field)) {
                f.add(field);
            }
            return new FieldSet(label, f, tokenize, bodyOnlyWhenImportant);
        }

        FieldSet without(String label, String field) {
            List<String> f = new ArrayList<>(fields);
            f.remove(field);
            return new FieldSet(label, f, tokenize, bodyOnlyWhenImportant);
        }
    }

    record Eval(int score, int leak, int promptChars) {
    }

    @Override
    public void run(String... args) throws Exception {
        JsonNode cand = new ObjectMapper().readTree(Path.of(candidatesFile).toFile());
        List<String> context = strings(cand.get("context"));
        Set<String> tokenize = new LinkedHashSet<>();
        List<String> pool = new ArrayList<>();
        for (JsonNode c : cand.get("candidates")) {
            String f = c.get("field").asText();
            String v = c.get("verdict").asText();
            if (context.contains(f)) {
                continue;
            }
            if ("토큰화".equals(v)) {
                tokenize.add(f);
            } else if (pool.size() < maxCandidates && c.get("clue").asInt() >= minClue) {
                pool.add(f);
            }
        }
        List<String> baseFields = new ArrayList<>(context);
        baseFields.addAll(tokenize);
        FieldSet base = new FieldSet("기준", baseFields, tokenize, true);

        List<String> hand = strings(cand.get("hand"));
        FieldSet handSet = new FieldSet("손으로 고른 10칸", hand,
                Set.of("logRecord.traceId", "logRecord.spanId", "logRecord.parentSpanId"), true);

        Path runDir = Path.of(props.resultsDir()).toAbsolutePath()
                .resolve("fieldsearch_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        Files.createDirectories(runDir);
        List<Scenario> select = selectIds.stream().map(scenarios::get).toList();
        List<Scenario> validate = validateIds.stream().map(scenarios::get).toList();
        log.info("AI 모드 {} · 고르는 장애 {} · 검증 장애 {} · 기준 {}칸 · 후보 {}개",
                ai.mode(), selectIds, validateIds, baseFields.size(), pool.size());

        Map<String, Object> summary = new LinkedHashMap<>();
        try (BufferedWriter w = Files.newBufferedWriter(runDir.resolve("steps.csv"), StandardCharsets.UTF_8);
             CSVPrinter csv = new CSVPrinter(w, CSVFormat.DEFAULT.builder()
                     .setHeader("phase", "set", "added_field", "scenario", "fields", "leak", "judge", "prompt_chars", "answer").build())) {
            int baseScore, baseLeak, handValScore, handValLeak;
            List<Map<String, Object>> gains = new ArrayList<>();
            List<String> kept = new ArrayList<>();
            FieldSet chosen = base;
            Eval chosenSel;
            if (resumeFrom.isBlank()) {
                // 앞으로 넣기: 기준 + 후보 하나씩
                Eval baseEval = evaluate("select", base, null, select, csv);
                baseScore = baseEval.score();
                baseLeak = baseEval.leak();
                for (String f : pool) {
                    Eval e = evaluate("select", base.with("기준+" + f, f), f, select, csv);
                    int gain = e.score() - baseScore;
                    gains.add(Map.of("field", f, "score", e.score(), "gain", gain, "leak", e.leak(), "leakDelta", e.leak() - baseLeak));
                    if (gain > 0) {
                        kept.add(f);
                    }
                    log.info("  {} → 점수 {} (기준 {}, {}{})  누출 {}", f, e.score(), baseScore, gain >= 0 ? "+" : "", gain, e.leak());
                }
                for (String f : kept) {
                    chosen = chosen.with("알고리즘 목록", f);
                }
                chosenSel = evaluate("select", chosen, null, select, csv);
                Eval handVal = evaluate("validate", handSet, null, validate, csv);
                handValScore = handVal.score();
                handValLeak = handVal.leak();
            } else {
                // 앞 단계 결과를 이어받는다 (같은 후보·같은 장애로 이미 잰 값, 비용 절약)
                JsonNode prev = new ObjectMapper().readTree(Path.of(resumeFrom).toFile());
                baseScore = prev.at("/base/score").asInt();
                baseLeak = prev.at("/base/leak").asInt();
                for (JsonNode g : prev.get("gains")) {
                    gains.add(Map.of("field", g.get("field").asText(), "score", g.get("score").asInt(), "gain", g.get("gain").asInt(),
                            "leak", g.get("leak").asInt(), "leakDelta", g.get("leakDelta").asInt()));
                    if (g.get("gain").asInt() > 0) {
                        kept.add(g.get("field").asText());
                    }
                }
                for (String f : kept) {
                    chosen = chosen.with("알고리즘 목록", f);
                }
                chosenSel = new Eval(prev.at("/chosen/selectScore").asInt(), prev.at("/chosen/selectLeak").asInt(), 0);
                handValScore = prev.at("/hand/validateScore").asInt();
                handValLeak = prev.at("/hand/validateLeak").asInt();
                log.info("이어받음: {} · 합친 목록 점수 {} 누출 {}", resumeFrom, chosenSel.score(), chosenSel.leak());
            }

            // 되돌려 빼기: 합친 목록에서 쓸모가 작은 칸부터 하나씩 빼 보고, 점수가 떨어지지 않으면 뺀다
            //   (칸을 따로 잰 효과는 합치면 달라진다. 예: 본문이 다른 단서를 흐림)
            List<String> order = new ArrayList<>(kept);
            Map<String, Map<String, Object>> byField = new LinkedHashMap<>();
            gains.forEach(g -> byField.put((String) g.get("field"), g));
            order.sort((a, b) -> {
                int ga = (int) byField.get(a).get("gain"), gb = (int) byField.get(b).get("gain");
                return ga != gb ? Integer.compare(ga, gb)
                        : Integer.compare((int) byField.get(b).get("leak"), (int) byField.get(a).get("leak"));
            });
            FieldSet current = chosen;
            Eval currentEval = chosenSel;
            List<Map<String, Object>> pruning = new ArrayList<>();
            for (String f : order) {
                FieldSet without = current.without("빼기-" + f, f);
                Eval e = evaluate("prune", without, f, select, csv);
                boolean drop = e.score() >= currentEval.score();
                pruning.add(Map.of("field", f, "scoreWithout", e.score(), "scoreWith", currentEval.score(),
                        "leakWithout", e.leak(), "dropped", drop));
                log.info("  빼 보기 {} → 점수 {} (지금 {}) 누출 {} → {}", f, e.score(), currentEval.score(), e.leak(),
                        drop ? "뺌" : "남김");
                if (drop) {
                    current = without;
                    currentEval = e;
                }
            }
            FieldSet finalSet = new FieldSet("알고리즘 최종", current.fields(), current.tokenize(), current.bodyOnlyWhenImportant());
            Eval finalVal = evaluate("validate", finalSet, null, validate, csv);

            summary.put("aiMode", ai.mode());
            summary.put("model", ai.model());
            summary.put("select", selectIds);
            summary.put("validate", validateIds);
            summary.put("maxScorePerSet", 2 * select.size());
            summary.put("base", Map.of("fields", base.fields(), "score", baseScore, "leak", baseLeak));
            summary.put("gains", gains);
            summary.put("chosen", Map.of("fields", chosen.fields(), "selectScore", chosenSel.score(), "selectLeak", chosenSel.leak()));
            summary.put("pruning", pruning);
            summary.put("final", Map.of("fields", finalSet.fields(), "selectScore", currentEval.score(), "selectLeak", currentEval.leak(),
                    "validateScore", finalVal.score(), "validateLeak", finalVal.leak()));
            summary.put("hand", Map.of("fields", hand, "validateScore", handValScore, "validateLeak", handValLeak));
            if (!resumeFrom.isBlank()) {
                summary.put("resumedFrom", resumeFrom);
            }
            writeTemplate(runDir.resolve("selected_template.yaml"), finalSet);
        }
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(runDir.resolve("summary.json").toFile(), summary);
        log.info("완료: {}", runDir);
        this.context.close();   // 예약 작업(파생본 삭제) 스레드가 JVM 을 붙잡지 않게 끝낸다
    }

    private Eval evaluate(String phase, FieldSet set, String added, List<Scenario> list, CSVPrinter csv) throws IOException {
        AllowlistFilter filter = new AllowlistFilter(TemplateLoader.of(List.of(template(set))), tokenizer, presidio);
        int score = 0, leak = 0, chars = 0;
        for (Scenario s : list) {
            FilterResult r = filter.apply(s.raw(), s.id(), PURPOSE);
            int residual = scorer.count(r.output(), s.injectedPii());
            AiAnswer a = ai.ask(r.output(), ExperimentConstants.FIXED_QUESTION);
            int judge = judge(s.id(), a);
            score += judge;
            leak += residual;
            chars += a.promptChars();
            csv.printRecord(phase, set.label(), added == null ? "" : added, s.id(), r.fieldsAfter(), residual, judge,
                    a.promptChars(), a.text());
            csv.flush();
        }
        return new Eval(score, leak, chars);
    }

    /** 정답 원인 설명과 답을 비교해 0·1·2 로 판정한다. 판정 실패는 0. */
    private int judge(String scenarioId, AiAnswer answer) {
        if (!"live".equals(answer.mode())) {
            return 0;
        }
        String truth = truths.get(scenarioId.split("_")[0]);
        String prompt = "장애의 실제 원인: " + truth + "\n\n"
                + "아래는 AI 의 장애 분석 답변이다. 답변이 장애가 난 서비스와 원인을 맞혔는지 판정하라.\n"
                + "2 = 서비스와 원인을 모두 맞힘, 1 = 서비스만 맞힘, 0 = 틀림 또는 장애 없음.\n"
                + "숫자 하나만 출력하라.\n\n답변:\n" + answer.text();
        AiAnswer j = ai.complete(prompt);
        Matcher m = DIGIT.matcher(j.text());
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    private static PurposeTemplate template(FieldSet set) {
        Map<String, FieldAction> actions = new LinkedHashMap<>();
        for (String f : set.tokenize()) {
            if (set.fields().contains(f)) {
                actions.put(f, FieldAction.TOKENIZE_CASE_SCOPED);
            }
        }
        return new PurposeTemplate(PURPOSE, set.label(), "필드 탐색", set.fields(), actions,
                PurposeTemplate.UnknownFieldPolicy.DROP, false, PurposeTemplate.InnerScanEngine.REGEX, set.bodyOnlyWhenImportant());
    }

    /** 게이트웨이에 그대로 넣을 수 있는 목록 파일. 보안팀이 검토·승인한 뒤 templates/ 에 둔다. */
    private static void writeTemplate(Path path, FieldSet set) throws IOException {
        StringBuilder sb = new StringBuilder("# 필드 탐색 결과 (FieldSearchRunner). 검토·승인 전 초안\n")
                .append("purposeId: INCIDENT_ANALYSIS_AUTO\ndisplayName: 장애 원인 분석 (알고리즘 제안)\n")
                .append("description: 필드 선택 알고리즘이 제안한 목록\nrequiredFields:\n");
        set.fields().forEach(f -> sb.append("  - ").append(f).append('\n'));
        sb.append("fieldActions:\n");
        set.tokenize().stream().filter(set.fields()::contains)
                .forEach(f -> sb.append("  ").append(f).append(": TOKENIZE_CASE_SCOPED\n"));
        sb.append("onUnknownField: DROP\ninnerScanOnKeptFields: false\nbodyOnlyWhenImportant: ")
                .append(set.bodyOnlyWhenImportant()).append('\n');
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}
