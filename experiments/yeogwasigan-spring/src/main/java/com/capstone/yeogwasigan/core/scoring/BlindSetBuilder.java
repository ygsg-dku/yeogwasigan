package com.capstone.yeogwasigan.core.scoring;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import com.capstone.yeogwasigan.core.scenario.ScenarioRepository;

/**
 * 블라인드 채점 파일 생성 / 채점 결과 병합.
 *
 * <p>목적: "우리 방식(담기)이니까 후하게 줬다"는 의심을 차단한다.
 *
 * <h3>prepare</h3>
 * results.csv 의 AI 응답에서 A/B/C 라벨을 제거하고 무작위로 섞어서
 * <ul>
 *   <li>blind/blind_answers.csv — 채점자에게는 이 파일만 전달 (blind_id, scenario_id, ai_answer, rca_score, memo)</li>
 *   <li>blind/blind_key.csv — 매핑표 (blind_id → filter, run_no). 채점이 끝날 때까지 열지 않는다</li>
 *   <li>blind/rubric.md — 시나리오별 정답 요약과 0~3점 채점 기준만 (함정·조건 정보는 뺌)</li>
 * </ul>
 *
 * <h3>merge</h3>
 * 채점자가 채운 rca_score 를 매핑표로 되돌려 results_scored.csv 를 만들고 방식별 평균을 돌려준다.
 *
 * <h3>라벨 누출 줄이기 (normalize, 기본 켜짐)</h3>
 * 응답 안에 TX_1A2B3C4D(담기), &lt;EMAIL_ADDRESS&gt;(빼기), 원문 이메일(그대로) 같은 흔적이 남아 있으면
 * 채점자가 조건을 짐작할 수 있다. 그래서 토큰·마스킹 표식·16~32자리 hex ID·주입 민감값을 모두 같은 [가림]으로 바꾼다.
 * (완벽한 블라인드는 아니다. 응답 문체나 언급한 단서 자체로 짐작할 여지는 남는다)
 */
@Component
public class BlindSetBuilder {

    public static final String MASK = "[가림]";

    private static final List<Pattern> MARKER_PATTERNS = List.of(
            Pattern.compile("\\b[A-Z][A-Z_]{1,24}_[0-9A-F]{8}\\b"),   // TX_xxxx, PS_xxxx, EMAIL_xxxx ...
            Pattern.compile("<[A-Z][A-Z_]+>"),                          // <EMAIL_ADDRESS>, <PERSON> ...
            Pattern.compile("\\b[0-9a-fA-F]{16,32}\\b"));               // 원문 traceId / spanId

    static final String[] ANSWER_HEADER = {"blind_id", "scenario_id", "ai_answer", "rca_score", "memo"};
    static final String[] KEY_HEADER = {"blind_id", "scenario_id", "filter", "run_no"};

    private final ScenarioRepository scenarios;

    public BlindSetBuilder(ScenarioRepository scenarios) {
        this.scenarios = scenarios;
    }

    public record PrepareResult(Path answers, Path key, Path rubric, int count) {
    }

    /** 방식별 요약 한 줄. */
    public record FilterSummary(String filter, int n, int scored, double meanRcaScore, double meanResidual) {
    }

    public record MergeResult(Path scored, List<FilterSummary> summary, int missingScores) {
    }

    // ------------------------------------------------------------------
    // prepare
    // ------------------------------------------------------------------

    public PrepareResult prepare(Path resultsCsv, Long seed, boolean normalize) {
        List<Map<String, String>> rows = readCsv(resultsCsv);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            order.add(i);
        }
        Collections.shuffle(order, seed == null ? new Random() : new Random(seed));

        List<List<String>> blindRows = new ArrayList<>();
        List<List<String>> keyRows = new ArrayList<>();
        int n = 1;
        for (int idx : order) {
            Map<String, String> r = rows.get(idx);
            String blindId = String.format("B%03d", n++);
            String answer = r.getOrDefault("ai_answer", "");
            if (normalize) {
                answer = normalizeAnswer(answer, r.get("scenario_id"));
            }
            blindRows.add(java.util.Arrays.asList(blindId, r.get("scenario_id"), answer, "", ""));
            keyRows.add(java.util.Arrays.asList(blindId, r.get("scenario_id"), r.get("filter"), r.get("run_no")));
        }

        Path dir = resultsCsv.toAbsolutePath().getParent().resolve("blind");
        Path answers = dir.resolve("blind_answers.csv");
        Path key = dir.resolve("blind_key.csv");
        Path rubric = dir.resolve("rubric.md");
        writeCsv(answers, ANSWER_HEADER, blindRows);
        writeCsv(key, KEY_HEADER, keyRows);
        writeRubric(rubric, rows.stream().map(r -> r.get("scenario_id")).distinct().toList());
        return new PrepareResult(answers, key, rubric, rows.size());
    }

    /** 응답 안에서 조건을 짐작하게 하는 흔적을 [가림]으로 바꾼다. */
    public String normalizeAnswer(String text, String scenarioId) {
        String out = text == null ? "" : text;
        for (String v : scenarios.piiValues(scenarioId)) {   // 긴 값부터 (부분 문자열 문제 방지)
            out = out.replace(v, MASK);
        }
        for (Pattern p : MARKER_PATTERNS) {
            out = p.matcher(out).replaceAll(java.util.regex.Matcher.quoteReplacement(MASK));
        }
        return out;
    }

    /** 채점자용 기준표: 정답 요약 + 채점 기준만. trap / affected_filters / in_allowlist_fields 같은 조건 정보는 싣지 않는다. */
    @SuppressWarnings("unchecked")
    private void writeRubric(Path path, List<String> scenarioIds) {
        StringBuilder sb = new StringBuilder("# 채점 기준 (rca_score, 0~3점)\n\n");
        sb.append("blind_answers.csv 의 각 응답을 아래 기준으로 채점해 rca_score 칸에 0~3 정수를 적어 주세요.\n");
        sb.append("응답이 어떤 방식으로 만들어졌는지는 알려 드리지 않습니다. [가림] 은 가려진 식별자·민감값입니다.\n\n");
        for (String id : scenarioIds) {
            Map<String, Object> truth = scenarios.exists(id) ? scenarios.truth(id) : Map.of();
            sb.append("## ").append(id).append('\n');
            if (truth.get("title") != null) {
                sb.append('\n').append(truth.get("title")).append('\n');
            }
            if (truth.get("root_cause") instanceof Map<?, ?> rc && rc.get("summary") != null) {
                sb.append("\n**정답(근본 원인)**: ").append(String.valueOf(rc.get("summary")).strip()).append('\n');
            }
            if (truth.get("rca_rubric") instanceof Map<?, ?> rubric) {
                sb.append("\n| 점수 | 기준 |\n|---|---|\n");
                List<Map.Entry<Object, Object>> entries = new ArrayList<>(((Map<Object, Object>) rubric).entrySet());
                entries.sort((a, b) -> String.valueOf(b.getKey()).compareTo(String.valueOf(a.getKey())));
                for (Map.Entry<Object, Object> e : entries) {
                    sb.append("| ").append(e.getKey()).append(" | ")
                            .append(String.valueOf(e.getValue()).replace("|", "\\|").strip()).append(" |\n");
                }
            } else {
                sb.append("\n(truth.yaml 에 rca_rubric 이 없습니다)\n");
            }
            sb.append('\n');
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------
    // merge
    // ------------------------------------------------------------------

    public MergeResult merge(Path resultsCsv) {
        Path dir = resultsCsv.toAbsolutePath().getParent().resolve("blind");
        Map<String, Map<String, String>> key = new LinkedHashMap<>();
        for (Map<String, String> r : readCsv(dir.resolve("blind_key.csv"))) {
            key.put(r.get("blind_id"), r);
        }
        Map<String, String> scores = new LinkedHashMap<>();
        for (Map<String, String> r : readCsv(dir.resolve("blind_answers.csv"))) {
            Map<String, String> k = key.get(r.get("blind_id"));
            if (k == null) {
                throw new IllegalStateException("매핑표에 없는 blind_id: " + r.get("blind_id"));
            }
            scores.put(rowKey(k.get("scenario_id"), k.get("filter"), k.get("run_no")), r.getOrDefault("rca_score", "").strip());
        }

        List<Map<String, String>> rows = readCsv(resultsCsv);
        if (rows.isEmpty()) {
            throw new IllegalStateException("results.csv 가 비어 있습니다: " + resultsCsv);
        }
        int missing = 0;
        for (Map<String, String> r : rows) {
            String s = scores.getOrDefault(rowKey(r.get("scenario_id"), r.get("filter"), r.get("run_no")), r.getOrDefault("rca_score", ""));
            r.put("rca_score", s);
            if (s == null || s.isBlank()) {
                missing++;
            }
        }

        Path out = resultsCsv.toAbsolutePath().getParent().resolve("results_scored.csv");
        String[] header = rows.get(0).keySet().toArray(String[]::new);
        List<List<String>> body = new ArrayList<>();
        for (Map<String, String> r : rows) {
            List<String> line = new ArrayList<>();
            for (String h : header) {
                line.add(r.get(h));
            }
            body.add(line);
        }
        writeCsv(out, header, body);
        return new MergeResult(out, summarize(rows), missing);
    }

    private static List<FilterSummary> summarize(List<Map<String, String>> rows) {
        Map<String, List<Map<String, String>>> byFilter = new LinkedHashMap<>();
        for (Map<String, String> r : rows) {
            byFilter.computeIfAbsent(r.get("filter"), k -> new ArrayList<>()).add(r);
        }
        List<FilterSummary> out = new ArrayList<>();
        byFilter.forEach((filter, list) -> {
            double scoreSum = 0;
            int scored = 0;
            double residualSum = 0;
            for (Map<String, String> r : list) {
                residualSum += parseOr(r.get("residual_pii_count"), 0);
                String s = r.get("rca_score");
                if (s != null && !s.isBlank()) {
                    scoreSum += parseOr(s, 0);
                    scored++;
                }
            }
            out.add(new FilterSummary(filter, list.size(), scored,
                    scored > 0 ? scoreSum / scored : Double.NaN, residualSum / list.size()));
        });
        return out;
    }

    private static double parseOr(String s, double fallback) {
        try {
            return Double.parseDouble(s.strip());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String rowKey(String scenario, String filter, String runNo) {
        return scenario + "\u0000" + filter + "\u0000" + runNo;
    }

    // ------------------------------------------------------------------
    // CSV 입출력 (UTF-8 + BOM: 엑셀에서 한글이 깨지지 않도록)
    // ------------------------------------------------------------------

    static List<Map<String, String>> readCsv(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            reader.mark(1);
            if (reader.read() != '﻿') {   // BOM 이 있으면 건너뛴다
                reader.reset();
            }
            CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
            List<Map<String, String>> rows = new ArrayList<>();
            try (CSVParser parser = format.parse(reader)) {
                List<String> header = parser.getHeaderNames();
                for (CSVRecord rec : parser) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (String h : header) {
                        row.put(h, rec.isMapped(h) && rec.isSet(h) ? rec.get(h) : "");
                    }
                    rows.add(row);
                }
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("CSV 를 읽을 수 없습니다: " + path, e);
        }
    }

    static void writeCsv(Path path, String[] header, List<List<String>> rows) {
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                w.write('﻿');
                try (CSVPrinter printer = new CSVPrinter(w, CSVFormat.DEFAULT.builder().setHeader(header).build())) {
                    for (List<String> row : rows) {
                        printer.printRecord(row);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("CSV 를 쓸 수 없습니다: " + path, e);
        }
    }
}
