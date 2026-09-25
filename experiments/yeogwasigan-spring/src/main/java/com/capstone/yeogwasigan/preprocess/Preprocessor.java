package com.capstone.yeogwasigan.preprocess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.capstone.yeogwasigan.core.log.LogFormat;

/**
 * 공통 전처리. 세 필터(그대로/빼기/담기)보다 먼저, 세 조건에 똑같이 적용한다.
 *
 * <ol>
 *   <li>① 실험 장비 로그 제거 — 지정한 서비스의 레코드, 장애 스위치 속성 키, 지정 문자열이 든 body</li>
 *   <li>② 반복 로그 줄이기 — 같은 묶음(서비스·심각도·메시지 패턴·상태코드·경로)은 시간순 처음 k건 + 마지막 m건
 *       + 가장 느린 s건만 남긴다. WARN 이상 또는 HTTP 5xx 는 줄이지 않고 전부 남긴다.</li>
 *   <li>⑤ 크기 확인 — '그대로' 조건 기준 추정 토큰 수를 계산한다(상한 판정은 호출하는 쪽에서).</li>
 * </ol>
 * 정답 노출 문장 제거(③)와 민감정보 주입(④)은 아직 없다.
 *
 * <p>출력 레코드는 시간순(timeUnixNano, 없으면 observedTimeUnixNano)으로 정렬된다. 값 자체는 바꾸지 않는다.
 */
public final class Preprocessor {

    /** Envoy(frontend-proxy) 기본 접속 로그: [시각] "메서드 경로 프로토콜" 상태 플래그 상세 종료상세 "실패사유" 수신 송신 소요ms ... */
    static final Pattern ENVOY = Pattern.compile(
            "^\\[[^\\]]*\\] \"(\\S+) (\\S+) [^\"]*\" (\\d+) \\S+ \\S+ \\S+ \"[^\"]*\" \\d+ \\d+ (\\d+) ");
    /** 숫자가 섞인 6자 이상 토큰(상품 ID·해시·UUID 조각 등) */
    private static final Pattern MIXED_TOKEN = Pattern.compile("\\b(?=[A-Za-z_-]*\\d)[A-Za-z0-9_-]{6,}\\b");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    /** OTel severityNumber: 13 = WARN */
    private static final int WARN = 13;

    private final PreprocessProperties props;

    public Preprocessor(PreprocessProperties props) {
        this.props = props;
    }

    /** 전처리 결과와 단계별 통계. */
    public record Result(List<Map<String, Object>> records, Map<String, Object> report) {
    }

    public Result apply(List<Map<String, Object>> input) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("inputRecords", input.size());

        // ---------------- ① 실험 장비 로그 제거 ----------------
        Map<String, Integer> droppedByService = new TreeMap<>();
        int droppedByBody = 0;
        Map<String, Integer> removedAttrKeys = new TreeMap<>();
        List<Map<String, Object>> step1 = new ArrayList<>();
        for (Map<String, Object> rec : LogFormat.deepCopy(input)) {
            Map<String, Object> flat = LogFormat.flatten(rec);
            String service = str(flat.get("resource.service.name"));
            if (props.excludeServices().contains(service)) {
                droppedByService.merge(service, 1, Integer::sum);
                continue;
            }
            String body = str(flat.get("logRecord.body"));
            if (props.excludeBodyContains().stream().anyMatch(body::contains)) {
                droppedByBody++;
                continue;
            }
            for (String layer : List.of("scope", "logRecord")) {
                if (rec.get(layer) instanceof Map<?, ?> layerMap && layerMap.get("attributes") instanceof Map<?, ?> attrs) {
                    attrs.keySet().removeIf(k -> {
                        boolean hit = props.excludeAttributePrefixes().stream().anyMatch(String.valueOf(k)::startsWith);
                        if (hit) {
                            removedAttrKeys.merge(String.valueOf(k), 1, Integer::sum);
                        }
                        return hit;
                    });
                }
            }
            step1.add(rec);
        }
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("droppedByService", droppedByService);
        r1.put("droppedByBodyContains", droppedByBody);
        r1.put("removedAttributeKeys", removedAttrKeys);
        r1.put("remainingRecords", step1.size());
        report.put("step1_excludeTestEquipment", r1);

        // ---------------- ② 반복 로그 줄이기 ----------------
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < step1.size(); i++) {
            items.add(Item.of(i, step1.get(i)));
        }
        items.sort(Comparator.comparingLong(Item::time).thenComparingInt(Item::order));

        Map<String, List<Item>> groups = new LinkedHashMap<>();
        for (Item it : items) {
            groups.computeIfAbsent(it.key(), k -> new ArrayList<>()).add(it);
        }
        TreeSet<Integer> keep = new TreeSet<>();   // 남길 레코드의 Item.order
        int keptImportant = 0;
        for (List<Item> g : groups.values()) {
            for (int i = 0; i < g.size(); i++) {
                Item it = g.get(i);
                if (it.important()) {
                    keep.add(it.order());
                    keptImportant++;
                } else if (i < props.keepFirst() || i >= g.size() - props.keepLast()) {
                    keep.add(it.order());
                }
            }
            g.stream().filter(it -> it.durationMs() >= 0)
                    .sorted(Comparator.comparingLong(Item::durationMs).reversed())
                    .limit(props.keepSlowest())
                    .forEach(it -> keep.add(it.order()));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item it : items) {
            if (keep.contains(it.order())) {
                out.add(it.record());
            }
        }
        long maxEnvoyMs = items.stream().mapToLong(Item::durationMs).max().orElse(-1);
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("groups", groups.size());
        r2.put("keptImportant(WARN+/5xx)", keptImportant);
        r2.put("keptTotal", out.size());
        r2.put("droppedRepeats", step1.size() - out.size());
        r2.put("maxProxyDurationMs", maxEnvoyMs);
        r2.put("topGroupsBefore", topGroups(groups, 8));
        report.put("step2_reduceRepeats", r2);

        // ---------------- ⑤ 크기 확인 ----------------
        String serialized = LogFormat.serialize(out);
        Map<String, Object> r5 = new LinkedHashMap<>();
        r5.put("outputRecords", out.size());
        r5.put("distinctFields", LogFormat.distinctFields(out).size());
        r5.put("chars", serialized.length());
        r5.put("estimatedTokensPassthrough", estimateTokens(serialized));
        r5.put("maxInputTokens", props.maxInputTokens());
        report.put("step5_size", r5);
        return new Result(out, report);
    }

    /** 이 데이터(JSON 로그)에서 OpenAI 가 보고한 토큰 수와 문자수/4 가 거의 일치했다(S1 담기: 771,848자 → 193,000). */
    public static int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / 4.0);
    }

    private static List<String> topGroups(Map<String, List<Item>> groups, int n) {
        return groups.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(n)
                .map(e -> e.getValue().size() + "x " + abbreviate(e.getKey(), 140))
                .toList();
    }

    // ------------------------------------------------------------------

    /** 묶음 판정에 필요한 값만 뽑아 둔 레코드. */
    record Item(int order, Map<String, Object> record, long time, String key, boolean important, long durationMs) {

        static Item of(int order, Map<String, Object> rec) {
            Map<String, Object> f = LogFormat.flatten(rec);
            String service = str(f.get("resource.service.name"));
            String body = str(f.get("logRecord.body"));
            int sevNum = toInt(f.get("logRecord.severityNumber"));
            String severity = sevNum > 0 ? String.valueOf(sevNum) : str(f.get("logRecord.severityText"));
            String method = str(f.get("logRecord.attributes.http.request.method"));
            String path = firstNonBlank(str(f.get("logRecord.attributes.url.template")),
                    str(f.get("logRecord.attributes.url.path")));
            String status = str(f.get("logRecord.attributes.http.response.status_code"));
            long duration = -1;
            String message;

            Matcher m = ENVOY.matcher(body);
            if (m.find()) {
                // 프록시 접속 로그: 쿼리스트링 때문에 body 가 매번 달라지므로 메서드·경로·상태코드로 묶는다
                method = m.group(1);
                if (path.isEmpty()) {
                    path = m.group(2).split("\\?", 2)[0];
                }
                status = m.group(3);
                duration = Long.parseLong(m.group(4));
                message = "<envoy access log>";
            } else {
                message = normalize(body);
            }
            String key = String.join(" | ", service, severity, method, path, status, message);
            boolean important = sevNum >= WARN || toInt(status) >= 500;
            long time = toLong(f.get("logRecord.timeUnixNano"));
            if (time == 0) {
                time = toLong(f.get("logRecord.observedTimeUnixNano"));
            }
            return new Item(order, rec, time, key, important, duration);
        }
    }

    /** 메시지 패턴: 숫자 섞인 토큰은 *, 숫자는 # 으로 가린다. */
    static String normalize(String body) {
        String s = MIXED_TOKEN.matcher(body).replaceAll("*");
        s = DIGITS.matcher(s).replaceAll("#");
        return abbreviate(s, 200);
    }

    private static String abbreviate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String firstNonBlank(String a, String b) {
        return !a.isEmpty() ? a : b;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int toInt(Object o) {
        long v = toLong(o);
        return v > Integer.MAX_VALUE ? 0 : (int) v;
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? 0 : Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
