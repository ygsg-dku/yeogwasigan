package com.capstone.yeogwasigan.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.capstone.yeogwasigan.core.log.LogFormat;

/**
 * Residual Scan: 승인·전송 직전, 파생본에 비밀값(Secret)과 금지 atom 이 남아 있으면 막는다.
 * (정책 명세 POLICY-01 4·5, POLICY-04 6, TASK-03-02, T-INT-02)
 *
 * <ul>
 *   <li><b>독립 규칙</b>: 담기 필터·참고 경고({@code WarningRules})와 규칙·코드를 공유하지 않는다.
 *       앞 단계가 놓친 값을 다시 잡는 장치라서, 같은 규칙이면 같은 곳에서 같이 놓친다.</li>
 *   <li><b>fail-closed</b>: 스캔 중 예외·null 입력은 PASS 가 아니라 {@code RESIDUAL_SCAN_FAILED} 로 막는다.</li>
 *   <li><b>값을 되돌려 주지 않는다</b>: 결과에는 위치(기록 순번·칸)·규칙·길이만 담는다.
 *       감사 기록과 화면에 비밀값이 다시 새면 안 된다.</li>
 * </ul>
 *
 * 입력은 {@code LogFormat} 내부 표준 형식(resource / scope / logRecord) 레코드 목록이다.
 */
public final class ResidualScanner {

    public static final String REASON_SECRET_FOUND = "RESIDUAL_SECRET_FOUND";
    public static final String REASON_ATOM_FOUND = "RESIDUAL_PROHIBITED_ATOM_FOUND";
    public static final String REASON_SCAN_FAILED = "RESIDUAL_SCAN_FAILED";

    public enum Status { PASS, DENIED }

    public enum Kind { SECRET, PROHIBITED_ATOM }

    /**
     * 발견 하나. 값은 담지 않는다.
     *
     * @param record 파생본 안의 기록 순번(0부터)
     * @param field  칸 경로(예: logRecord.attributes.api_secret)
     * @param ruleId 걸린 규칙
     * @param length 걸린 값의 길이(값 자체 대신 규모만 남긴다)
     */
    public record Finding(int record, String field, String ruleId, Kind kind, int length) {
    }

    public record Result(Status status, String reasonCode, List<Finding> findings) {

        public boolean passed() {
            return status == Status.PASS;
        }

        public long secretCount() {
            return findings.stream().filter(f -> f.kind() == Kind.SECRET).count();
        }

        static Result pass() {
            return new Result(Status.PASS, null, List.of());
        }

        static Result failed() {
            return new Result(Status.DENIED, REASON_SCAN_FAILED, List.of());
        }
    }

    // ------------------------------------------------------------------ 규칙

    private record ValueRule(String id, Pattern pattern, int group) {
    }

    /** 값 안에서 모양으로 알아보는 비밀값. 칸 이름과 무관하게 모든 문자열 값에 적용한다. */
    private static final List<ValueRule> VALUE_RULES = List.of(
            new ValueRule("PRIVATE_KEY", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"), 0),
            new ValueRule("AWS_ACCESS_KEY", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), 0),
            new ValueRule("STRIPE_KEY", Pattern.compile("\\b[sr]k_(?:live|test)_[A-Za-z0-9]{8,}"), 0),
            new ValueRule("SK_API_KEY", Pattern.compile("\\bsk-(?:proj-|ant-|svcacct-)?[A-Za-z0-9_-]{20,}"), 0),
            new ValueRule("GITHUB_TOKEN", Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{30,})"), 0),
            new ValueRule("SLACK_TOKEN", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"), 0),
            new ValueRule("GOOGLE_API_KEY", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"), 0),
            new ValueRule("JWT", Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), 0),
            new ValueRule("BEARER_TOKEN", Pattern.compile("(?i)\\bBearer\\s+([A-Za-z0-9._~+/=-]{16,})"), 1),
            new ValueRule("URL_CREDENTIAL", Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s/:@]+:([^\\s/@]+)@"), 1),
            // password=..., "api_key": "...", client_secret: ... 처럼 이름과 값이 같은 문자열에 있는 경우
            new ValueRule("SECRET_ASSIGNMENT", Pattern.compile(
                    "(?i)\\b(?:api[_-]?(?:key|secret)|(?:client|app|secret)[_-]?(?:secret|key)|secret|password|passwd|pwd"
                            + "|private[_-]?key|(?:access|auth|refresh|id|api|bearer)[_-]?token|x-api-key|authorization|cvv|cvc)"
                            + "\\b[\"']?\\s*[:=]\\s*[\"']?(?![{<\\[$*])([^\\s\"',;&})]{4,})"), 1));

    /** 구조화된 칸 이름이 비밀값을 가리키는 경우(값 모양은 상관없다). 정규화한 이름의 끝과 비교한다. */
    private static final Set<String> SECRET_FIELD_NAMES = Set.of(
            "apisecret", "apikey", "secret", "secretkey", "clientsecret", "password", "passwd", "pwd",
            "privatekey", "accesstoken", "authtoken", "refreshtoken", "bearertoken", "xapikey", "authorization",
            "credential", "credentials", "cvv", "cvc", "cardcvv");

    /** 정책 명세 POLICY-02 의 DROP·DROP atom. 칸 이름으로만 판단한다(값 모양 규칙은 참고 경고 몫). */
    private static final Set<String> PROHIBITED_FIELD_NAMES = Set.of(
            "customername", "accountnumber");

    /** 값이 이미 가려졌거나 토큰화된 경우는 남은 비밀값이 아니다. */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)^(?:[\\s\\p{Punct}]*|\\[?\\s*(?:secret|value|token)?\\s*(?:removed|redacted|masked|dropped)\\s*\\]?|<[^>]*>"
                    + "|\\*{3,}|x{4,}|null|none|undefined|true|false|(?:tx|ps)_[A-Za-z0-9_-]+|\\{[^}]*\\}|\\$\\{[^}]*\\})$");

    // ------------------------------------------------------------------ 스캔

    /** 파생본 전체를 검사한다. 발견이 하나라도 있으면 DENIED, 스캔 자체가 실패해도 DENIED(fail-closed). */
    public Result scan(List<Map<String, Object>> payload) {
        try {
            if (payload == null) {
                return Result.failed();
            }
            List<Finding> found = new ArrayList<>();
            for (int i = 0; i < payload.size(); i++) {
                Map<String, Object> flat = LogFormat.flatten(payload.get(i));
                Set<String> seen = new LinkedHashSet<>();
                for (Map.Entry<String, Object> e : flat.entrySet()) {
                    if (e.getValue() != null) {
                        scanField(i, e.getKey(), String.valueOf(e.getValue()), seen, found);
                    }
                }
            }
            if (found.isEmpty()) {
                return Result.pass();
            }
            boolean secret = found.stream().anyMatch(f -> f.kind() == Kind.SECRET);
            return new Result(Status.DENIED, secret ? REASON_SECRET_FOUND : REASON_ATOM_FOUND, List.copyOf(found));
        } catch (RuntimeException e) {
            return Result.failed();
        }
    }

    private static void scanField(int record, String field, String value, Set<String> seen, List<Finding> out) {
        boolean placeholder = isPlaceholder(value);
        if (!placeholder && nameIn(field, PROHIBITED_FIELD_NAMES)) {
            add(out, seen, record, field, "PROHIBITED_FIELD_NAME", Kind.PROHIBITED_ATOM, value.length());
        }
        if (!placeholder && nameIn(field, SECRET_FIELD_NAMES)) {
            add(out, seen, record, field, "SECRET_FIELD_NAME", Kind.SECRET, value.length());
        }
        for (ValueRule rule : VALUE_RULES) {
            Matcher m = rule.pattern().matcher(value);
            while (m.find()) {
                String hit = m.group(rule.group());
                if (hit != null && !isPlaceholder(hit)) {
                    add(out, seen, record, field, rule.id(), Kind.SECRET, hit.length());
                }
            }
        }
    }

    private static void add(List<Finding> out, Set<String> seen, int record, String field, String ruleId, Kind kind,
                            int length) {
        if (seen.add(field + "|" + ruleId)) {
            out.add(new Finding(record, field, ruleId, kind, length));
        }
    }

    private static boolean isPlaceholder(String value) {
        return PLACEHOLDER.matcher(value.strip()).matches();
    }

    /**
     * 칸 경로의 마지막 토막(또는 마지막 두 토막)이 이름 목록과 정확히 같은지 본다.
     * 키 안에 점이 있어도("client.secret") 잡히고, 더 긴 다른 단어의 일부("bypassword")는 잡지 않는다.
     */
    private static boolean nameIn(String path, Set<String> names) {
        String[] parts = path.split("\\.");
        String last = normalize(parts[parts.length - 1]);
        if (names.contains(last)) {
            return true;
        }
        return parts.length >= 2 && names.contains(normalize(parts[parts.length - 2]) + last);
    }

    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
