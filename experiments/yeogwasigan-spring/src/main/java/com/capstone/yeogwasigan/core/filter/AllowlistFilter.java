package com.capstone.yeogwasigan.core.filter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.capstone.yeogwasigan.core.config.ExperimentConstants;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.presidio.PresidioClient;
import com.capstone.yeogwasigan.core.template.PurposeTemplate;
import com.capstone.yeogwasigan.core.template.PurposeTemplate.FieldAction;
import com.capstone.yeogwasigan.core.template.PurposeTemplate.InnerScanEngine;
import com.capstone.yeogwasigan.core.template.TemplateLoader;
import com.capstone.yeogwasigan.core.tokenize.CaseScopedTokenizer;

/**
 * [C] 담기 (ALLOWLIST) — 업무 목적(Purpose Template)에 필요하다고 미리 정한 필드만 골라서 전송.
 *
 * <h3>동작 순서 (레코드마다)</h3>
 * <ol>
 *   <li>필드를 평탄화한다. 예) logRecord.attributes.user.email</li>
 *   <li>담을 목록 = requiredFields ∪ fieldActions 의 키.
 *       목록에 없으면 onUnknownField 규칙(DROP)에 따라 무조건 버린다.
 *       → 처음 보는 필드가 새로 생겨도 자동으로 안 나간다. (담기의 핵심)</li>
 *   <li>fieldActions 가 있는 필드는 값을 사건 단위 토큰/가명으로 바꾼다.</li>
 *   <li>innerScanOnKeptFields=true 이면, 원문 그대로 담은 필드(특히 logRecord.body 같은 자유 텍스트)를
 *       한 번 더 스캔해서 민감값이 있는 "그 부분만" 토큰으로 치환한다.
 *       자유 텍스트는 키 이름으로 분류할 수 없기 때문에 필요한 단계다.</li>
 * </ol>
 *
 * <h3>한계 (일부러 남겨 둔 것)</h3>
 * <ul>
 *   <li>목록에 없는 필드에 핵심 단서가 있으면 그 단서도 같이 버려진다.
 *       → 샘플 시나리오의 INFO 로그 함정이 이 경우를 측정한다.</li>
 *   <li>정규식 내부 재검사는 이름처럼 형식이 없는 값을 잡지 못한다.</li>
 * </ul>
 */
@Component
public class AllowlistFilter implements LogFilter {

    private final TemplateLoader templates;
    private final CaseScopedTokenizer tokenizer;
    private final PresidioClient presidio;   // innerScanEngine=presidio 일 때만 사용

    public AllowlistFilter(TemplateLoader templates, CaseScopedTokenizer tokenizer, PresidioClient presidio) {
        this.templates = templates;
        this.tokenizer = tokenizer;
        this.presidio = presidio;
    }

    @Override
    public String name() {
        return ExperimentConstants.ALLOWLIST;
    }

    @Override
    public String label() {
        return "담기";
    }

    @Override
    public FilterResult apply(List<Map<String, Object>> logs, String caseId) {
        return apply(logs, caseId, ExperimentConstants.PURPOSE_ID);
    }

    @Override
    public FilterResult apply(List<Map<String, Object>> logs, String caseId, String purposeId) {
        PurposeTemplate tpl = templates.get(purposeId);
        Set<String> allowed = tpl.allowedFields();
        Map<String, FieldAction> actions = tpl.fieldActions();

        Map<String, String> transformed = new LinkedHashMap<>();
        Map<String, Integer> innerHits = new TreeMap<>();
        InnerScanner scanner = tpl.innerScanEngine() == InnerScanEngine.PRESIDIO
                ? (text) -> presidioInnerScan(text, caseId, transformed, innerHits)
                : (text) -> regexInnerScan(text, caseId, transformed, innerHits);

        List<Map<String, Object>> output = new ArrayList<>(logs.size());
        for (Map<String, Object> rec : logs) {
            Map<String, Object> kept = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : LogFormat.flatten(rec).entrySet()) {
                String path = e.getKey();
                Object value = e.getValue();

                // ① 목록에 없으면 버린다 (onUnknownField: DROP)
                if (!allowed.contains(path)) {
                    continue;
                }

                FieldAction action = actions.get(path);
                if (action != null) {
                    // ② 변환해서 담는다 — 빈 값(예: 최상위 span 의 parentSpanId)은 그대로
                    if (value == null || "".equals(value)) {
                        kept.put(path, value);
                    } else {
                        String token = tokenizer.tokenize(value, caseId, action.prefix());
                        transformed.put(String.valueOf(value), token);
                        kept.put(path, token);
                    }
                } else if (tpl.innerScanOnKeptFields()) {
                    // ③ 원문 그대로 담되, 내부를 한 번 더 검사
                    kept.put(path, LogFormat.mapStrings(value, scanner::scan));
                } else {
                    kept.put(path, value);
                }
            }
            output.add(LogFormat.unflatten(kept));
        }

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("purposeId", tpl.purposeId());
        notes.put("innerScanEngine", tpl.innerScanEngine().name());
        notes.put("innerScanHits", innerHits);
        return FilterResult.of(logs, output, transformed, notes);
    }

    @FunctionalInterface
    private interface InnerScanner {
        String scan(String text);
    }

    // ------------------------------------------------------------------
    // 내부 재검사 — 정규식 (innerScanEngine: regex)
    // 위에서부터 순서대로 적용한다. 치환 결과(예: EMAIL_1A2B3C4D)는 다음 패턴에 걸리지 않는다.
    // ------------------------------------------------------------------

    private record InnerPattern(String kind, Pattern pattern, Predicate<MatchResult> check) {
    }

    private static final Pattern DATE_LIKE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    static final List<InnerPattern> INNER_SCAN_PATTERNS = List.of(
            new InnerPattern("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), m -> true),
            // 주민등록번호
            new InnerPattern("RRN", Pattern.compile("(?<!\\d)\\d{6}-[1-4]\\d{6}(?!\\d)"), m -> true),
            new InnerPattern("CARD", Pattern.compile("(?<!\\d)(?:\\d{4}-){3}\\d{4}(?!\\d)|(?<!\\d)\\d{16}(?!\\d)"), m -> true),
            new InnerPattern("PHONE", Pattern.compile("(?<!\\d)01[016789]-?\\d{3,4}-?\\d{4}(?!\\d)"), m -> true),
            // 계좌번호 (하이픈으로 3~4덩어리, 숫자 10~16자리, 날짜 형식 제외)
            new InnerPattern("ACCT", Pattern.compile("(?<![\\d-])\\d{2,6}(?:-\\d{2,8}){2,3}(?![\\d-])"),
                    AllowlistFilter::isAccount),
            new InnerPattern("IP", Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])"),
                    AllowlistFilter::isIpv4));

    private static boolean isAccount(MatchResult m) {
        String s = m.group();
        long digits = s.chars().filter(Character::isDigit).count();
        return digits >= 10 && digits <= 16 && !DATE_LIKE.matcher(s).matches();
    }

    /** 0~255 네 덩어리, 앞자리 0 금지(파이썬 ipaddress 와 같은 기준). */
    private static boolean isIpv4(MatchResult m) {
        for (String part : m.group().split("\\.")) {
            if (part.length() > 1 && part.startsWith("0")) {
                return false;
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private String regexInnerScan(String text, String caseId, Map<String, String> transformed, Map<String, Integer> hits) {
        String result = text;
        for (InnerPattern p : INNER_SCAN_PATTERNS) {
            Matcher matcher = p.pattern().matcher(result);
            result = matcher.replaceAll(m -> {
                if (!p.check().test(m)) {
                    return Matcher.quoteReplacement(m.group());
                }
                String token = tokenizer.tokenize(m.group(), caseId, p.kind() + "_");
                transformed.put(m.group(), token);
                hits.merge(p.kind(), 1, Integer::sum);
                return Matcher.quoteReplacement(token);
            });
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 내부 재검사 — Presidio (innerScanEngine: presidio)
    // [B] 빼기와 똑같은 Presidio 기본 엔진으로 탐지하고, 치환만 사건 단위 토큰으로 한다.
    // ------------------------------------------------------------------

    private String presidioInnerScan(String text, String caseId, Map<String, String> transformed, Map<String, Integer> hits) {
        List<PresidioClient.Detection> results = new ArrayList<>(presidio.analyze(text));
        results.sort(Comparator.comparingInt(PresidioClient.Detection::start)
                .thenComparingInt(d -> -(d.end() - d.start())));
        // 겹치는 탐지는 먼저 시작하는(같으면 더 긴) 것만 남긴다
        List<PresidioClient.Detection> spans = new ArrayList<>();
        int lastEnd = -1;
        for (PresidioClient.Detection d : results) {
            if (d.start() >= lastEnd) {
                spans.add(d);
                lastEnd = d.end();
            }
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = spans.size() - 1; i >= 0; i--) {
            PresidioClient.Detection d = spans.get(i);
            String token = tokenizer.tokenize(d.original(), caseId, d.entityType() + "_");
            transformed.put(d.original(), token);
            hits.merge(d.entityType(), 1, Integer::sum);
            sb.replace(d.start(), d.end(), token);
        }
        return sb.toString();
    }
}
