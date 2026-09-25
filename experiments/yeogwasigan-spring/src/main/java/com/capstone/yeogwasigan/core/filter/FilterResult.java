package com.capstone.yeogwasigan.core.filter;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.capstone.yeogwasigan.core.log.LogFormat;

/**
 * 세 필터의 공통 출력.
 *
 * @param output        필터링된 로그 (3층 구조 유지) — 이것만 외부 AI 로 나간다
 * @param fieldsBefore  원본의 서로 다른 필드 경로 수
 * @param fieldsAfter   실제로 나간 서로 다른 필드 경로 수
 * @param droppedFields 버려진 필드 경로 (원본에 있었는데 나가지 않은 것)
 * @param transformed   원본값 → 변환값 매핑. 원본 민감값이 들어 있으므로 웹 응답·결과 파일에는 넣지 않는다
 * @param notes         방식별 부가정보 (Presidio 탐지 유형별 건수, innerScan 적중 수 등)
 */
public record FilterResult(
        List<Map<String, Object>> output,
        int fieldsBefore,
        int fieldsAfter,
        List<String> droppedFields,
        Map<String, String> transformed,
        Map<String, Object> notes) {

    /**
     * 필드 수·버려진 필드는 세 필터 모두 이 한 곳에서 같은 방식으로 계산한다.
     * (필터마다 세는 방법이 다르면 "12개 → 5개" 같은 비교가 의미를 잃는다)
     */
    public static FilterResult of(List<Map<String, Object>> input,
                                  List<Map<String, Object>> output,
                                  Map<String, String> transformed,
                                  Map<String, Object> notes) {
        List<String> before = LogFormat.distinctFields(input);
        Set<String> after = Set.copyOf(LogFormat.distinctFields(output));
        List<String> dropped = before.stream().filter(f -> !after.contains(f)).toList();
        return new FilterResult(output, before.size(), after.size(), dropped,
                Map.copyOf(transformed), notes == null ? Map.of() : notes);
    }
}
