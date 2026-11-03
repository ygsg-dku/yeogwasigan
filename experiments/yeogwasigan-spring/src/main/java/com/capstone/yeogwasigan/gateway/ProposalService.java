package com.capstone.yeogwasigan.gateway;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.template.TemplateLoader;
import com.capstone.yeogwasigan.gateway.GatewayProperties.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * 목록 제안 (구조도의 E): 원인이 밝혀진 지난 장애 로그로 필드 선택 알고리즘을 돌려 목적 템플릿 초안을 만들고,
 * 승인자가 승인하면 게이트웨이 목록에 등록한다.
 *
 * <ol>
 *   <li>1단계 점수 매기기: 실제로 실행한다 (scripts/field_select.py, AI 없음).</li>
 *   <li>2~3단계 넣어 보기·빼 보기: <b>데모 모드</b>. AI 호출이 수십 번 필요해서, 같은 입력으로 돌린 가장 최근 실제 실행
 *       (results/fieldsearch_*)의 AI 판정을 작업 진행처럼 흘려보낸다. 화면과 결과에 데모 모드라고 적는다.</li>
 *   <li>4단계 검증: 위 실행의 결과를 그대로 쓴다.</li>
 * </ol>
 * 입력 장애 묶음은 요청에서 받지 않는다. 그 실행의 candidates.json 에 적힌 장애 폴더·고르는/검증 장애·문맥 칸 그대로다.
 *
 * ponytail: 제안은 메모리에만 둔다(서버를 다시 띄우면 사라짐). 2~3단계 실제 실행은 FieldSearchRunner 의 evaluate 를
 * 여기서 부르도록 바꾸면 된다(비용·시간 때문에 지금은 데모 모드만).
 */
@Service
public class ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ANSWER_MAX = 700;

    public enum Status { RUNNING, READY, APPROVED, REJECTED, FAILED }

    /** 진행 기록 한 줄. 화면은 seq 이후 것만 받아 이어 붙인다. */
    public record Event(int seq, String kind, String title, Object data) {
    }

    public static final class Proposal {
        public final String id;
        public final String requester;
        public final Instant createdAt = Instant.now();
        public volatile Status status = Status.RUNNING;
        public volatile String approver;
        public volatile String comment;
        public volatile String purposeId;
        public volatile String displayName;
        public volatile String templateYaml;
        public volatile List<String> fields = List.of();
        public volatile String error;
        public final String mode = "2~3단계 데모 모드";
        public final List<Event> events = new CopyOnWriteArrayList<>();

        Proposal(String id, String requester) {
            this.id = id;
            this.requester = requester;
        }

        void add(String kind, String title, Object data) {
            events.add(new Event(events.size(), kind, title, data));
        }
    }

    private final AppProperties props;
    private final GatewayProperties gateway;
    private final TemplateLoader templates;
    private final long paceMs;
    private final Map<String, Proposal> proposals = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "proposal"));
    private int seq;

    public ProposalService(AppProperties props, GatewayProperties gateway, TemplateLoader templates,
                           @Value("${yeogwasigan.proposal.pace-ms:1200}") long paceMs) {
        this.props = props;
        this.gateway = gateway;
        this.templates = templates;
        this.paceMs = paceMs;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    public synchronized Proposal start(String user) {
        if (!gateway.users().containsKey(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "게이트웨이 사용자가 아닙니다");
        }
        Proposal p = new Proposal("P-%03d".formatted(++seq), user);
        proposals.put(p.id, p);
        worker.submit(() -> run(p));
        return p;
    }

    public List<Proposal> list() {
        return proposals.values().stream().sorted(Comparator.comparing((Proposal p) -> p.id).reversed()).toList();
    }

    public Proposal get(String id) {
        Proposal p = proposals.get(id);
        if (p == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 제안: " + id);
        }
        return p;
    }

    /** 승인하면 게이트웨이 목록(목적 템플릿)에 등록된다. 승인자 역할이 있어야 하고, 자기 제안은 결정할 수 없다. */
    public synchronized Proposal decide(String id, String user, boolean approve, String comment) {
        Proposal p = get(id);
        if (!gateway.users().getOrDefault(user, List.of()).contains(Role.APPROVER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "승인자 권한이 없습니다.");
        }
        if (user.equals(p.requester)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "자기 제안은 승인하거나 반려할 수 없습니다.");
        }
        if (p.status != Status.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "결정할 수 있는 상태가 아닙니다: " + p.status);
        }
        p.approver = user;
        p.comment = comment;
        if (approve) {
            templates.register(TemplateLoader.parse(new ByteArrayInputStream(p.templateYaml.getBytes(StandardCharsets.UTF_8))));
            p.status = Status.APPROVED;
            p.add("decision", "승인 · 게이트웨이 목록에 등록", Map.of("by", user, "purposeId", p.purposeId, "name", p.displayName));
        } else {
            p.status = Status.REJECTED;
            p.add("decision", "반려", Map.of("by", user, "comment", comment == null ? "" : comment));
        }
        return p;
    }

    // ───────────────────────── 실행 ─────────────────────────

    private void run(Proposal p) {
        try {
            Path dir = latestRun();
            JsonNode summary = JSON.readTree(dir.resolve("summary.json").toFile());
            JsonNode input = JSON.readTree(dir.resolve("candidates.json").toFile());
            p.add("start", "입력: 원인이 밝혀진 지난 장애 로그", Map.of(
                    "select", input.path("select"), "validate", input.path("validate"), "context", input.path("context"),
                    "titles", titles(scenarioDir(input), input)));

            // 1단계: 실제 실행
            long t0 = System.nanoTime();
            JsonNode stage1 = scoreFields(input);
            List<String> tried = new ArrayList<>();
            summary.path("gains").forEach(g -> tried.add(g.path("field").asText()));
            Map<String, Object> s1 = new LinkedHashMap<>();
            s1.put("elapsedMs", (System.nanoTime() - t0) / 1_000_000);
            s1.put("fields", stage1.path("fields"));
            s1.put("tried", tried);
            p.add("stage1", "1단계 점수 매기기 (실제 실행)", s1);

            // 2~3단계: 데모 모드 — 같은 입력의 실제 실행 기록을 시도 하나씩 흘려보낸다
            p.add("mode", "2~3단계는 데모 모드: " + dir.getFileName() + " 실행(" + summary.path("model").asText()
                    + ")의 AI 판정을 사용", Map.of("run", dir.getFileName().toString()));
            int base = summary.path("base").path("score").asInt(), baseLeak = summary.path("base").path("leak").asInt();
            for (Map<String, Object> g : groups(dir.resolve("steps.csv"))) {
                String phase = (String) g.get("phase"), added = (String) g.get("added");
                if (phase.equals("validate")) {
                    continue;
                }
                pace();
                if (phase.equals("select") && added.isEmpty()) {
                    p.add("trial", (boolean) g.get("first") ? "기본 세트로 기준 점수" : "넣은 칸 합치기", trial(g, null, null, null));
                } else if (phase.equals("select")) {
                    JsonNode gain = find(summary.path("gains"), added);
                    boolean ok = gain.path("gain").asInt() > 0 && gain.path("leak").asInt() <= baseLeak;
                    p.add("trial", "넣어 보기: " + added, trial(g, base, ok ? "넣음" : "안 넣음",
                            ok ? "" : gain.path("gain").asInt() <= 0 ? "점수가 오르지 않음" : "민감값이 늘어남"));
                } else {
                    JsonNode pr = find(summary.path("pruning"), added);
                    boolean dropped = pr.path("dropped").asBoolean();
                    p.add("trial", "빼 보기: " + added, trial(g, pr.path("scoreWith").asInt(), dropped ? "뺌" : "남김",
                            dropped ? "없어도 점수가 떨어지지 않음" : "빼면 점수가 떨어짐"));
                }
            }

            // 4단계: 검증 결과
            pace();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("validate", summary.path("validate"));
            v.put("final", summary.path("final"));
            v.put("hand", summary.path("hand"));
            v.put("base", summary.path("base"));
            v.put("compare", summary.path("compare"));
            v.put("allFields", stage1.path("fields").size());
            p.add("validate", "4단계 처음 보는 장애로 검증", v);

            // 제안 목록 → 템플릿 초안
            List<String> fields = new ArrayList<>();
            summary.path("final").path("fields").forEach(n -> fields.add(n.asText()));
            List<String> tokenize = new ArrayList<>();
            stage1.path("fields").forEach(f -> {
                if ("토큰화".equals(f.path("verdict").asText()) && fields.contains(f.path("field").asText())) {
                    tokenize.add(f.path("field").asText());
                }
            });
            p.fields = List.copyOf(fields);
            p.purposeId = "INCIDENT_ANALYSIS_" + p.id.replace("-", "");
            p.displayName = "장애 원인 분석 (제안 " + p.id + ")";
            p.templateYaml = yaml(p, fields, tokenize);
            p.add("ready", "제안 목록 완성 · 승인 대기", Map.of("fields", fields, "tokenize", tokenize, "yaml", p.templateYaml));
            p.status = Status.READY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("목록 제안 {} 실패", p.id, e);
            p.error = e.getMessage();
            p.status = Status.FAILED;
            p.add("error", "실패", Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    private void pace() throws InterruptedException {
        Thread.sleep(paceMs);
    }

    /** 1단계: field_select.py 를 하위 프로세스로. 인자는 실행 기록의 입력 그대로(요청 값을 넣지 않는다). */
    private JsonNode scoreFields(JsonNode in) throws IOException, InterruptedException {
        Path out = Files.createTempFile("field_select", ".json");
        try {
            List<String> cmd = List.of("python3", "scripts/field_select.py", "--dir", scenarioDir(in).toString(),
                    "--normal", in.path("normal").asText("S0_normal"), "--select", join(in.path("select")),
                    "--validate", join(in.path("validate")), "--context", join(in.path("context")), "--json", out.toString());
            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String text = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!proc.waitFor(60, TimeUnit.SECONDS) || proc.exitValue() != 0) {
                proc.destroyForcibly();
                throw new IllegalStateException("1단계 실행 실패: " + text.strip());
            }
            return JSON.readTree(out.toFile());
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private static Map<String, Object> trial(Map<String, Object> g, Integer before, String decision, String why) {
        Map<String, Object> m = new LinkedHashMap<>(g);
        m.remove("first");
        if (before != null) {
            m.put("before", before);
        }
        if (decision != null) {
            m.put("decision", decision);
            m.put("why", why);
        }
        return m;
    }

    /** steps.csv 를 연속된 (단계, 목록, 넣은 칸) 묶음으로. 묶음 하나 = 같은 목록으로 장애 여러 개를 보낸 한 번의 시도. */
    private static List<Map<String, Object>> groups(Path csv) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        String last = null;
        try (CSVParser parser = CSVParser.parse(csv, StandardCharsets.UTF_8,
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            for (CSVRecord r : parser) {
                String key = r.get("phase") + "|" + r.get("set") + "|" + r.get("added_field");
                if (!key.equals(last)) {
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("phase", r.get("phase"));
                    g.put("added", r.get("added_field"));
                    g.put("first", out.isEmpty());
                    g.put("rows", new ArrayList<Map<String, Object>>());
                    out.add(g);
                    last = key;
                }
                String a = r.get("answer");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get(out.size() - 1).get("rows");
                rows.add(Map.of("scenario", r.get("scenario"), "judge", Integer.parseInt(r.get("judge")),
                        "leak", Integer.parseInt(r.get("leak")), "answer", a.length() > ANSWER_MAX ? a.substring(0, ANSWER_MAX) + "…" : a));
            }
        }
        return out;
    }

    private static JsonNode find(JsonNode arr, String field) {
        for (JsonNode n : arr) {
            if (field.equals(n.path("field").asText())) {
                return n;
            }
        }
        return JSON.createObjectNode();
    }

    private static String yaml(Proposal p, List<String> fields, List<String> tokenize) {
        StringBuilder y = new StringBuilder();
        y.append("purposeId: ").append(p.purposeId).append('\n')
                .append("displayName: '").append(p.displayName).append("'\n")
                .append("description: '필드 선택 알고리즘이 제안하고 승인자가 승인한 목록 (").append(p.mode).append(")'\n")
                .append("requiredFields:\n");
        fields.forEach(f -> y.append("  - ").append(f).append('\n'));
        if (!tokenize.isEmpty()) {
            y.append("fieldActions:\n");
            tokenize.forEach(f -> y.append("  ").append(f).append(": TOKENIZE_CASE_SCOPED\n"));
        }
        return y.append("onUnknownField: DROP\ninnerScanOnKeptFields: false\nbodyOnlyWhenImportant: true\n").toString();
    }

    /** 장애 ID → 정답지 제목 (화면 범례·툴팁용). truth.yaml 의 title 한 줄만 읽는다. */
    private static Map<String, String> titles(Path dir, JsonNode in) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        List<JsonNode> ids = new ArrayList<>();
        in.path("select").forEach(ids::add);
        in.path("validate").forEach(ids::add);
        for (JsonNode id : ids) {
            Path truth = dir.resolve(id.asText()).resolve("truth.yaml");
            String title = Files.isRegularFile(truth) ? Files.readAllLines(truth, StandardCharsets.UTF_8).stream()
                    .filter(l -> l.startsWith("title:")).map(l -> l.substring(6).strip()).findFirst().orElse("") : "";
            out.put(id.asText(), title);
        }
        return out;
    }

    /**
     * 실행 기록에 적힌 장애 폴더. 기록은 실행한 컴퓨터의 절대 경로라 다른 컴퓨터에는 없을 수 있다 —
     * 그때는 프로젝트 안의 같은 이름 폴더(보통 scenarios)를 쓴다.
     */
    private static Path scenarioDir(JsonNode in) {
        Path p = Path.of(in.path("dir").asText());
        return Files.isDirectory(p) || p.getFileName() == null ? p : Path.of(p.getFileName().toString());
    }

    private static String join(JsonNode arr) {
        List<String> s = new ArrayList<>();
        arr.forEach(n -> s.add(n.asText()));
        return String.join(",", s);
    }

    /** 가장 최근 live 실행 폴더 (summary.json · candidates.json · steps.csv 가 다 있는 것). */
    private Path latestRun() throws IOException {
        Path root = Path.of(props.resultsDir()).toAbsolutePath();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : dirs.filter(x -> x.getFileName().toString().matches("fieldsearch_\\d{8}_\\d{6}"))
                    .sorted(Comparator.comparing((Path x) -> x.getFileName().toString()).reversed()).toList()) {
                if (Files.isRegularFile(d.resolve("summary.json")) && Files.isRegularFile(d.resolve("candidates.json"))
                        && Files.isRegularFile(d.resolve("steps.csv"))
                        && "live".equals(JSON.readTree(d.resolve("summary.json").toFile()).path("aiMode").asText())) {
                    return d;
                }
            }
        }
        throw new IllegalStateException("필드 탐색 실행 기록이 없습니다 (scripts/fieldsearch.sh 로 먼저 실행)");
    }
}
