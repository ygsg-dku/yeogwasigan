package com.capstone.yeogwasigan.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 참고 경고 기준 v0.1 ({@code docs/spec/14_APPROVAL_AND_WARNING.md}).
 *
 * <p>화이트리스트를 거친 파생본의 칸 값을 하나씩 검사해 민감값으로 보이는 곳을 찾는다. 파생본은 바꾸지 않는다.
 * <ul>
 *   <li>높음(HIGH): 새면 바로 개인·금융 피해로 이어지고, 모양과 검증 규칙으로 확실히 알아볼 수 있는 값. 승인이 필요하다</li>
 *   <li>낮음(LOW): 식별에 쓰일 수는 있지만 모양만으로는 개인과 무관한 값과 구분되지 않는 값. 표시만 한다</li>
 *   <li>제외: 사설 IP, 네트워크 주소·버전 문자열, 토큰화된 값, 시각·trace 칸. 세지 않는다</li>
 * </ul>
 * 규칙은 위에서부터 적용하고, 먼저 잡힌 자리는 뒤 규칙이 다시 잡지 않는다(예: {@code user.id=<UUID>} 는 식별자 하나로만 센다).
 */
public final class WarningRules {

    public enum Level { HIGH, LOW }

    /**
     * 경고 하나.
     *
     * @param record 파생본 안의 기록 순번(0부터)
     * @param field  칸 경로(예: logRecord.body)
     * @param value  잡힌 원문 값. 화면에는 {@link #masked()} 만 보낸다
     * @param start  칸 값 안에서의 시작 위치
     */
    public record Finding(int record, String field, String type, Level level, String value, int start, int end) {

        public String masked() {
            return mask(type, value);
        }
    }

    record Rule(String type, Level level, Pattern pattern, int group, Predicate<String> accept) {
    }

    private static final Predicate<String> ANY = v -> true;

    /** 적용 순서 = 우선순위. 앞 규칙이 잡은 자리는 뒤 규칙이 건너뛴다. */
    static final List<Rule> RULES = List.of(
            // 식별자: 사용자·세션·주문·거래·배송 ID 이름 바로 뒤의 값 (PROTOCOL v1.1 민감 키). 토큰화된 값은 이미 가명이다
            new Rule("ID", Level.HIGH, Pattern.compile(
                    "(?i)(?<![a-z])(?:session|user|customer|account|order|transaction|tracking)[._-]?id\"?\\s*[=:]\\s*\"?"
                            + "([A-Za-z0-9][A-Za-z0-9._-]{5,})"), 1,
                    v -> !v.startsWith("TX_") && !v.startsWith("PS_")),
            new Rule("EMAIL", Level.HIGH, Pattern.compile(
                    "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"), 0, ANY),
            // 주민등록번호·외국인등록번호: 생년월일 6자리 + 성별·국적 숫자 1~8
            new Rule("RRN", Level.HIGH, Pattern.compile(
                    "(?<!\\d)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])-[1-8]\\d{6}(?!\\d)"), 0, ANY),
            // 카드번호: 4자리씩 끊은 13~19자리 또는 붙여 쓴 13~16자리. 카드 브랜드 번호대(2~6으로 시작)이고 Luhn 검사를 통과해야 한다
            new Rule("CARD", Level.HIGH, Pattern.compile(
                    "(?<![\\d-])(?:\\d{4}[ -]){3}\\d{1,7}(?![\\d-])|(?<!\\d)\\d{13,16}(?!\\d)"), 0, WarningRules::isCard),
            // 휴대폰 번호
            new Rule("PHONE", Level.HIGH, Pattern.compile(
                    "(?<![\\d-])01[016789]-?\\d{3,4}-?\\d{4}(?![\\d-])"), 0, ANY),
            // 계좌번호: 하이픈으로 끊은 숫자 10~14자리. 날짜(YYYY-MM-DD)는 숫자가 8자리라 빠진다
            new Rule("ACCOUNT", Level.HIGH, Pattern.compile(
                    "(?<![\\w-])\\d{2,6}(?:-\\d{2,8}){1,3}(?![\\w-])"), 0, WarningRules::isAccount),
            // 이름 없이 나온 UUID: 요청 ID 같은 무해한 값과 모양이 같아 표시만 한다
            new Rule("UUID", Level.LOW, Pattern.compile(
                    "(?<![0-9A-Fa-f-])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![0-9A-Fa-f-])"),
                    0, ANY),
            // 공인 IPv4. 사설·루프백 대역, 끝자리 0(네트워크 주소·버전 문자열), '/' 뒤(브라우저 버전 등)는 제외
            new Rule("IPV4", Level.LOW, Pattern.compile(
                    "(?<![\\w./])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w.])"), 0, WarningRules::isPublicHostIp));

    /** 값을 검사하지 않는 칸(경로 끝 이름). 시각·trace 칸은 숫자·16진수 모양이 민감값과 겹친다 */
    private static final Set<String> SKIP_FIELDS = Set.of(
            "timeUnixNano", "observedTimeUnixNano", "traceId", "spanId", "parentSpanId");

    private WarningRules() {
    }

    /** 파생본 전체(모든 기록)를 검사한다. */
    public static List<Finding> scan(List<Map<String, Object>> payload) {
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < payload.size(); i++) {
            int record = i;
            walk(payload.get(i), "", (field, value) -> scanValue(record, field, value, out));
        }
        return out;
    }

    public static Level levelOf(String type) {
        return RULES.stream().filter(r -> r.type().equals(type)).map(Rule::level).findFirst().orElse(Level.HIGH);
    }

    private interface Visitor {
        void visit(String field, Object value);
    }

    private static void walk(Object node, String path, Visitor v) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, val) -> walk(val, path.isEmpty() ? String.valueOf(k) : path + "." + k, v));
        } else if (node instanceof List<?> list) {
            list.forEach(item -> walk(item, path, v));
        } else if (node != null) {
            v.visit(path, node);
        }
    }

    private static void scanValue(int record, String field, Object value, List<Finding> out) {
        if (skip(field)) {
            return;
        }
        String text = String.valueOf(value);
        List<int[]> taken = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                int s = m.start(rule.group());
                int e = m.end(rule.group());
                String v = m.group(rule.group());
                if (overlaps(taken, s, e) || !rule.accept().test(v)) {
                    continue;
                }
                taken.add(new int[] {s, e});
                out.add(new Finding(record, field, rule.type(), rule.level(), v, s, e));
            }
        }
    }

    private static boolean skip(String field) {
        String last = field.substring(field.lastIndexOf('.') + 1);
        return SKIP_FIELDS.contains(last) || field.toLowerCase().contains("version");
    }

    private static boolean overlaps(List<int[]> taken, int s, int e) {
        return taken.stream().anyMatch(t -> s < t[1] && t[0] < e);
    }

    // ------------------------------------------------------------------ 검증 규칙

    static boolean isCard(String v) {
        String d = v.replaceAll("\\D", "");
        if (d.length() < 13 || d.length() > 19 || d.charAt(0) < '2' || d.charAt(0) > '6') {
            return false;   // 1로 시작하는 13·19자리는 대부분 밀리초·나노초 시각이다
        }
        int sum = 0;
        for (int i = 0; i < d.length(); i++) {
            int x = d.charAt(d.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                x *= 2;
                if (x > 9) {
                    x -= 9;
                }
            }
            sum += x;
        }
        return sum % 10 == 0;
    }

    static boolean isAccount(String v) {
        int digits = v.replaceAll("\\D", "").length();
        return digits >= 10 && digits <= 14;
    }

    static boolean isPublicHostIp(String v) {
        String[] p = v.split("\\.");
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            if (p[i].length() > 1 && p[i].startsWith("0")) {
                return false;
            }
            o[i] = Integer.parseInt(p[i]);
            if (o[i] > 255) {
                return false;
            }
        }
        boolean privateOrLocal = o[0] == 10 || o[0] == 127 || o[0] == 0
                || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                || (o[0] == 192 && o[1] == 168)
                || (o[0] == 169 && o[1] == 254);
        return !privateOrLocal && o[3] != 0 && o[3] != 255;
    }

    // ------------------------------------------------------------------ 가림

    /** 승인 화면에 보낼 값. 승인자에게도 민감값은 최소한만 보인다. */
    static String mask(String type, String v) {
        switch (type) {
            case "EMAIL": {
                int at = v.indexOf('@');
                return v.charAt(0) + "***" + v.substring(at);
            }
            case "CARD": {
                String d = v.replaceAll("\\D", "");
                return "****-****-****-" + d.substring(d.length() - 4);
            }
            case "RRN":
                return v.substring(0, 6) + "-*******";
            case "PHONE": {
                String d = v.replaceAll("\\D", "");
                return d.substring(0, 3) + "-****-" + d.substring(d.length() - 4);
            }
            case "ACCOUNT": {
                String d = v.replaceAll("\\D", "");
                return "*".repeat(d.length() - 3) + d.substring(d.length() - 3);
            }
            case "IPV4": {
                String[] p = v.split("\\.");
                return p[0] + "." + p[1] + ".*.*";
            }
            default:
                return v.length() <= 4 ? "****" : v.substring(0, 4) + "****";
        }
    }
}
