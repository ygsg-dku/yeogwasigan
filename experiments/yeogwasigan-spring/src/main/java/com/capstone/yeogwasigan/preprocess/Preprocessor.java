package com.capstone.yeogwasigan.preprocess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.scenario.PiiItem;
import com.capstone.yeogwasigan.preprocess.SpanPiiExtractor.SpanValue;

/**
 * 공통 전처리. 세 필터(그대로/빼기/담기)보다 먼저, 세 조건에 똑같이 적용한다.
 *
 * <ol>
 *   <li>① 실험 장비 로그 제거 — 지정한 서비스의 레코드, 지정 문자열이 든 body (스위치 속성 키는 ③ 판정 뒤에 지운다)</li>
 *   <li>③ 주입 흔적 제거 (PROTOCOL v1.3) — 장애 에러 메시지의 스위치 문구는 문구만 지우고,
 *       그래도 body·속성에 스위치 이름·feature flag 문구가 남은 레코드는 통째로 뺀다. ② 보다 먼저 적용한다</li>
 *   <li>② 반복 로그 줄이기 — 같은 묶음(서비스·심각도·메시지 패턴·상태코드·경로)은 시간순 처음 k건 + 마지막 m건
 *       + 가장 느린 s건만 남긴다. WARN 이상 또는 HTTP 5xx 는 줄이지 않고 전부 남긴다.</li>
 *   <li>④ 민감정보 주입 (PROTOCOL v1.1 민감 키) — 원문의 span·로그에 있던 민감 키 값 중 전처리 결과에 이미 들어 있는 값은
 *       "자연 발생"으로 기록하고, span 에만 있던 값은 같은 traceId 의 로그에 옮겨 넣는다.
 *       넣는 자리는 키마다 속성 → 본문 → 속성 … 번갈아 (담기·빼기 어느 쪽에도 유리하지 않게)</li>
 *   <li>⑤ 크기 확인 — '그대로' 조건 기준 추정 토큰 수를 계산한다(상한 판정은 호출하는 쪽에서).</li>
 * </ol>
 * 적용 순서: ① → ③ → ② → ④ → ⑤. ③ 은 흔적 로그가 대표로 뽑히지 않게 ② 보다 먼저,
 * ④ 는 넣은 값이 반복 줄이기로 지워지지 않게 ② 뒤에 둔다.
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

    /**
     * 전처리 결과와 단계별 통계.
     *
     * @param pii 결과물에서 셀 민감값 (자연 발생 + 주입). injected_pii.yaml 로 저장해 잔존 채점에 쓴다
     */
    public record Result(List<Map<String, Object>> records, Map<String, Object> report, List<PiiItem> pii) {
    }

    /** span 재료 없이 ①②③⑤만 (④ 는 원문에 있던 자연 발생 값만 기록). */
    public Result apply(List<Map<String, Object>> input) {
        return apply(input, List.of());
    }

    /** @param spanValues 원문 span 의 민감 키 값 ({@link SpanPiiExtractor#extract}) */
    public Result apply(List<Map<String, Object>> input, List<SpanValue> spanValues) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("inputRecords", input.size());

        // ---------------- ① 실험 장비 로그 제거 ----------------
        Map<String, Integer> droppedByService = new TreeMap<>();
        int droppedByBody = 0;
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
            step1.add(rec);
        }
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("droppedByService", droppedByService);
        r1.put("droppedByBodyContains", droppedByBody);
        r1.put("remainingRecords", step1.size());
        report.put("step1_excludeTestEquipment", r1);

        // ---------------- ③ 주입 흔적 제거 ----------------
        Map<String, Integer> droppedByTerm = new TreeMap<>();
        Map<String, Integer> strippedByPhrase = new TreeMap<>();
        Map<String, Integer> removedAttrKeys = new TreeMap<>();
        List<Map<String, Object>> step3 = new ArrayList<>();
        for (Map<String, Object> rec : step1) {
            if (rec.get("logRecord") instanceof Map<?, ?> lr) {
                @SuppressWarnings("unchecked")
                Map<String, Object> logRecord = (Map<String, Object>) lr;
                logRecord.replaceAll((k, v) -> LogFormat.mapStrings(v, str -> strip(str, strippedByPhrase)));
                String term = firstContained(logRecord);
                if (term != null) {
                    droppedByTerm.merge(term, 1, Integer::sum);
                    continue;
                }
            }
            // 남은 레코드에서 스위치 속성 키를 지운다(① 규칙). 통째로 뺄지 판정한 뒤에 해야 흔적 있는 로그를 놓치지 않는다
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
            step3.add(rec);
        }
        Map<String, Object> r3 = new LinkedHashMap<>();
        r3.put("strippedValuesByPhrase", strippedByPhrase);
        r3.put("droppedByTerm", droppedByTerm);
        r3.put("removedAttributeKeys", removedAttrKeys);
        r3.put("remainingRecords", step3.size());
        report.put("step3_removeInjectionTraces", r3);

        // ---------------- ② 반복 로그 줄이기 ----------------
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < step3.size(); i++) {
            items.add(Item.of(i, step3.get(i)));
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
        r2.put("droppedRepeats", step3.size() - out.size());
        r2.put("maxProxyDurationMs", maxEnvoyMs);
        r2.put("topGroupsBefore", topGroups(groups, 8));
        report.put("step2_reduceRepeats", r2);

        // ---------------- ④ 민감정보 주입 ----------------
        List<PiiItem> pii = new ArrayList<>();
        report.put("step4_injectPii", injectPii(input, out, spanValues, pii));

        // ---------------- ⑤ 크기 확인 ----------------
        String serialized = LogFormat.serialize(out);
        Map<String, Object> r5 = new LinkedHashMap<>();
        r5.put("outputRecords", out.size());
        r5.put("distinctFields", LogFormat.distinctFields(out).size());
        r5.put("chars", serialized.length());
        r5.put("estimatedTokensPassthrough", estimateTokens(serialized));
        r5.put("maxInputTokens", props.maxInputTokens());
        report.put("step5_size", r5);
        return new Result(out, report, pii);
    }

    private Map<String, Object> injectPii(List<Map<String, Object>> input, List<Map<String, Object>> out,
                                          List<SpanValue> spanValues, List<PiiItem> pii) {
        Map<String, Object> r4 = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>(props.piiKeys());
        if (keys.isEmpty()) {
            r4.put("skipped", "pii-keys 가 비어 있음");
            return r4;
        }
        // 원문(span + 로그 속성)에 있던 민감 키 값. PROTOCOL v1.1: 6자 이상
        Map<String, String> candidates = new LinkedHashMap<>();   // 값 → 키 (먼저 나온 키)
        for (SpanValue sv : spanValues) {
            if (sv.value().length() >= props.piiMinLength()) {
                candidates.putIfAbsent(sv.value(), sv.key());
            }
        }
        for (Map<String, Object> rec : input) {
            Map<String, Object> flat = LogFormat.flatten(rec);
            for (String key : props.piiKeys()) {
                if (flat.get("logRecord.attributes." + key) instanceof String v && v.length() >= props.piiMinLength()) {
                    candidates.putIfAbsent(v, key);
                }
            }
        }

        // 1) 자연 발생: 전처리 결과에 이미 그대로 들어 있는 값
        String text = LogFormat.serialize(out);
        Set<String> used = new HashSet<>();
        Map<String, Integer> naturalByKey = new TreeMap<>();
        candidates.forEach((value, key) -> {
            if (text.contains(value)) {
                used.add(value);
                naturalByKey.merge(key, 1, Integer::sum);
                pii.add(new PiiItem(nextId(pii), key, value, "자연 발생: 원본 로그에 이미 있던 값"));
            }
        });

        // 2) 주입: span 에만 있던 값을 같은 traceId 의 로그로 옮긴다
        Map<String, List<Integer>> byTrace = new HashMap<>();
        for (int i = 0; i < out.size(); i++) {
            Object traceId = LogFormat.flatten(out.get(i)).get("logRecord.traceId");
            if (traceId instanceof String t && !t.isEmpty()) {
                byTrace.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
            }
        }
        Map<String, Integer> injectedByKey = new TreeMap<>();
        Map<String, Integer> noMatchByKey = new TreeMap<>();
        Set<String> recordKeyUsed = new HashSet<>();
        for (SpanValue sv : spanValues) {
            int n = injectedByKey.getOrDefault(sv.key(), 0);
            if (sv.value().length() < props.piiMinLength() || used.contains(sv.value()) || n >= props.piiMaxPerKey()) {
                continue;
            }
            Integer target = pickTarget(out, byTrace.get(sv.traceId()), sv, recordKeyUsed);
            if (target == null) {
                noMatchByKey.merge(sv.key(), 1, Integer::sum);
                continue;
            }
            String where = inject(out.get(target), sv, n % 2 == 0);
            recordKeyUsed.add(target + "|" + sv.key());
            used.add(sv.value());
            injectedByKey.put(sv.key(), n + 1);
            pii.add(new PiiItem(nextId(pii), sv.key(), sv.value(),
                    "주입: " + where + " ← span(" + sv.service() + ", traceId " + sv.traceId() + ")"));
        }
        r4.put("candidateValues", candidates.size());
        r4.put("naturalByKey", naturalByKey);
        r4.put("injectedByKey", injectedByKey);
        r4.put("noMatchingLogByKey", noMatchByKey);
        r4.put("totalPii", pii.size());
        return r4;
    }

    /** 같은 traceId 로그 중 span 과 같은 서비스를 먼저, 없으면 시간순 첫 로그. 같은 키를 두 번 넣지 않는다. */
    private static Integer pickTarget(List<Map<String, Object>> out, List<Integer> sameTrace, SpanValue sv,
                                      Set<String> recordKeyUsed) {
        if (sameTrace == null) {
            return null;
        }
        Integer fallback = null;
        for (int i : sameTrace) {
            if (recordKeyUsed.contains(i + "|" + sv.key())) {
                continue;
            }
            if (sv.service().equals(LogFormat.flatten(out.get(i)).get("resource.service.name"))) {
                return i;
            }
            if (fallback == null) {
                fallback = i;
            }
        }
        return fallback;
    }

    /** 속성으로 넣거나 본문 끝에 " 키=값" 으로 붙인다. 본문이 문자열이 아니면 속성으로. 넣은 자리를 돌려준다. */
    @SuppressWarnings("unchecked")
    private static String inject(Map<String, Object> rec, SpanValue sv, boolean asAttribute) {
        Map<String, Object> lr = (Map<String, Object>) rec.computeIfAbsent("logRecord", k -> new LinkedHashMap<>());
        if (!asAttribute && lr.get("body") instanceof String body) {
            lr.put("body", body + " " + sv.key() + "=" + sv.value());
            return "logRecord.body";
        }
        ((Map<String, Object>) lr.computeIfAbsent("attributes", k -> new LinkedHashMap<String, Object>()))
                .put(sv.key(), sv.value());
        return "logRecord.attributes." + sv.key();
    }

    private static String nextId(List<PiiItem> pii) {
        return String.format("PII-%03d", pii.size() + 1);
    }

    /** stripPhrases 를 지운다. 지운 값의 개수를 문구별로 센다. */
    private String strip(String s, Map<String, Integer> counter) {
        String out = s;
        for (String phrase : props.stripPhrases()) {
            if (out.contains(phrase)) {
                out = out.replace(phrase, "");
                counter.merge(phrase, 1, Integer::sum);
            }
        }
        return out;
    }

    /** logRecord 안의 문자열 값과 속성 키 중 dropIfContains 에 걸리는 첫 문자열. 없으면 null */
    private String firstContained(Object value) {
        if (value instanceof String s) {
            return props.dropIfContains().stream().filter(s::contains).findFirst().orElse(null);
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String hit = firstContained(String.valueOf(e.getKey()));
                if (hit == null) {
                    hit = firstContained(e.getValue());
                }
                if (hit != null) {
                    return hit;
                }
            }
        }
        if (value instanceof List<?> list) {
            for (Object v : list) {
                String hit = firstContained(v);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
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
