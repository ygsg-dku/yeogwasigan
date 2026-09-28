package com.capstone.yeogwasigan.web;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.config.ExperimentConstants;
import com.capstone.yeogwasigan.core.scoring.KeywordScorer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 실험 러너 결과(results/&lt;실행시각&gt;/) 보기 API. 결과 화면(results.html)이 쓴다.
 *
 * <pre>
 * GET /api/runs                               실행 기록 목록 (최신 먼저)
 * GET /api/runs/{runId}                       행 전체 + 시나리오×방식 요약 + 키워드 자동 채점
 * GET /api/runs/{runId}/payload?scenario=&amp;filter=&amp;run=   그 호출에서 AI 로 실제로 보낸 로그
 * </pre>
 *
 * 자동 채점은 결과 파일에 적힌 값이 아니라 읽을 때 {@link KeywordScorer} 로 다시 계산한다.
 * 채점 칸이 없던 예전 실행(리허설)도 같은 기준으로 볼 수 있게 하기 위해서다. 오류·mock 응답은 채점하지 않는다.
 */
@RestController
@RequestMapping("/api/runs")
public class ResultsController {

    private static final Pattern RUN_ID = Pattern.compile("\\d{8}_\\d{6}");
    private static final Pattern SCENARIO_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final int PAYLOAD_MAX_LINES = 300;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AppProperties props;
    private final KeywordScorer keywordScorer;

    public ResultsController(AppProperties props, KeywordScorer keywordScorer) {
        this.props = props;
        this.keywordScorer = keywordScorer;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        Path root = resultsRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(d -> RUN_ID.matcher(d.getFileName().toString()).matches())
                    .filter(d -> Files.isRegularFile(d.resolve("results.csv")))
                    .sorted(Comparator.comparing((Path d) -> d.getFileName().toString()).reversed())
                    .toList()) {
                Map<String, Object> meta = readMeta(dir);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", dir.getFileName().toString());
                m.put("startedAt", meta.get("startedAt"));
                m.put("aiModel", meta.get("aiModel"));
                m.put("aiMode", meta.get("aiMode"));
                m.put("runs", meta.get("runs"));
                m.put("filters", meta.get("filters"));
                m.put("scenarios", meta.get("scenarios"));
                m.put("rows", readRows(dir).size());
                out.add(m);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @GetMapping("/{runId}")
    public Map<String, Object> detail(@PathVariable String runId) {
        Path dir = runDir(runId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, String> r : readRows(dir)) {
            String scenario = r.getOrDefault("scenario_id", "");
            String filter = r.getOrDefault("filter", "");
            String runNo = r.getOrDefault("run_no", "");
            String answer = r.getOrDefault("ai_answer", "");
            String mode = r.getOrDefault("ai_mode", "");
            if (mode.isBlank()) {
                mode = inferMode(answer);   // ai_mode 칸이 없던 예전 결과
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scenario", scenario);
            row.put("filter", filter);
            row.put("run", parseInt(runNo));
            row.put("fieldsBefore", parseInt(r.get("fields_before")));
            row.put("fieldsAfter", parseInt(r.get("fields_after")));
            row.put("residual", parseInt(r.get("residual_pii_count")));
            row.put("aiMode", mode);
            row.put("answer", answer);
            row.put("auto", "live".equals(mode) ? keywordScorer.score(scenario, answer).orElse(null) : null);
            row.put("hasPayload", Files.isRegularFile(payloadPath(dir, scenario, filter, runNo)));
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", runId);
        out.put("meta", readMeta(dir));
        Map<String, Object> scoring = new LinkedHashMap<>();
        scoring.put("available", keywordScorer.available());
        scoring.put("meta", keywordScorer.meta());
        scoring.put("rules", keywordScorer.rules());
        out.put("scoring", scoring);
        out.put("rows", rows);
        out.put("summary", summarize(rows));
        return out;
    }

    @GetMapping("/{runId}/payload")
    public Map<String, Object> payload(@PathVariable String runId, @RequestParam String scenario,
                                       @RequestParam String filter, @RequestParam int run) {
        if (!SCENARIO_ID.matcher(scenario).matches() || !ExperimentConstants.FILTER_NAMES.contains(filter)) {
            throw new IllegalArgumentException("잘못된 요청입니다.");
        }
        Path p = payloadPath(runDir(runId), scenario, filter, String.valueOf(run));
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("보낸 로그 파일이 없습니다: " + p.getFileName());
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(p, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", lines.size());
        out.put("chars", lines.stream().mapToInt(String::length).sum() + lines.size());
        out.put("truncated", lines.size() > PAYLOAD_MAX_LINES);
        out.put("text", String.join("\n", lines.subList(0, Math.min(lines.size(), PAYLOAD_MAX_LINES))));
        return out;
    }

    // ------------------------------------------------------------------

    /** 시나리오 × 방식별로 적중 수·평균 잔존을 모은다. 시나리오는 결과에 나온 순서, 방식은 실험 순서. */
    private static List<Map<String, Object>> summarize(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> cells = new LinkedHashMap<>();
        for (String filter : ExperimentConstants.FILTER_NAMES) {
            for (Map<String, Object> r : rows) {
                if (!filter.equals(r.get("filter"))) {
                    continue;
                }
                String key = r.get("scenario") + "|" + filter;
                Map<String, Object> c = cells.computeIfAbsent(key, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("scenario", r.get("scenario"));
                    m.put("filter", filter);
                    m.put("n", 0);
                    m.put("errors", 0);
                    m.put("scored", 0);
                    m.put("serviceHits", 0);
                    m.put("causeHits", 0);
                    m.put("residualSum", 0);
                    m.put("fieldsAfter", r.get("fieldsAfter"));
                    return m;
                });
                inc(c, "n");
                c.put("residualSum", (int) c.get("residualSum") + (int) r.get("residual"));
                if (!"live".equals(r.get("aiMode"))) {
                    inc(c, "errors");
                }
                if (r.get("auto") instanceof KeywordScorer.Score s) {
                    inc(c, "scored");
                    if (s.serviceHit()) {
                        inc(c, "serviceHits");
                    }
                    if (s.causeHit()) {
                        inc(c, "causeHits");
                    }
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(cells.values());
        for (Map<String, Object> c : out) {
            int n = (int) c.get("n");
            c.put("residualAvg", n == 0 ? 0 : Math.round((int) c.remove("residualSum") * 10.0 / n) / 10.0);
        }
        return out;
    }

    private static void inc(Map<String, Object> m, String key) {
        m.put(key, (int) m.get(key) + 1);
    }

    private Path resultsRoot() {
        return Path.of(props.resultsDir()).toAbsolutePath().normalize();
    }

    private Path runDir(String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("잘못된 실행 ID 입니다: " + runId);
        }
        Path dir = resultsRoot().resolve(runId);
        if (!Files.isRegularFile(dir.resolve("results.csv"))) {
            throw new IllegalArgumentException("실행 기록을 찾을 수 없습니다: " + runId);
        }
        return dir;
    }

    /** ExperimentRunner 가 쓰는 이름: payloads/&lt;시나리오&gt;__&lt;방식&gt;__run&lt;n&gt;.jsonl */
    private static Path payloadPath(Path dir, String scenario, String filter, String runNo) {
        return dir.resolve("payloads").resolve(scenario + "__" + filter + "__run" + runNo + ".jsonl");
    }

    private static List<Map<String, String>> readRows(Path dir) {
        try {
            String text = Files.readString(dir.resolve("results.csv"), StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) {
                text = text.substring(1);   // 엑셀용 BOM
            }
            try (CSVParser parser = CSVParser.parse(new StringReader(text),
                    CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
                List<Map<String, String>> rows = new ArrayList<>();
                for (CSVRecord rec : parser) {
                    rows.add(rec.toMap());
                }
                return rows;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> readMeta(Path dir) {
        Path p = dir.resolve("run_meta.json");
        if (!Files.isRegularFile(p)) {
            return Map.of();
        }
        try {
            return JSON.readValue(p.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static String inferMode(String answer) {
        if (answer.startsWith("[AI 호출 오류]")) {
            return "error";
        }
        return answer.startsWith("[MOCK") ? "mock" : "live";
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
