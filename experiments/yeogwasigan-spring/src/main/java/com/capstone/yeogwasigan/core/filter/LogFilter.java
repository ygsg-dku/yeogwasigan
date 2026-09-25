package com.capstone.yeogwasigan.core.filter;

import java.util.List;
import java.util.Map;

/**
 * 세 필터([A] 그대로 · [B] 빼기 · [C] 담기)의 공통 인터페이스.
 *
 * <p>실험이 성립하려면 A/B/C 의 차이가 "오직 필터 방식" 때문이어야 한다. 그래서
 * <ul>
 *   <li>입력: 모두 같은 레코드 목록(resource / scope / logRecord 3층 구조) + 같은 caseId</li>
 *   <li>출력: 모두 같은 {@link FilterResult} (output 역시 같은 3층 구조 레코드)</li>
 * </ul>
 * 로 통일한다. 필터를 거친 로그는 같은 직렬화(LogFormat.serialize)·같은 질문·같은 AI 설정으로 전송된다.
 *
 * <p>구현체는 입력 레코드를 절대 수정하지 않는다(같은 원본으로 세 필터를 차례로 돌리기 때문).
 */
public interface LogFilter {

    /** 필터 이름: PASSTHROUGH | DENYLIST | ALLOWLIST (결과 CSV 의 filter 열 값). */
    String name();

    /** 화면 표시용 한국어 이름: 그대로 | 빼기 | 담기. */
    String label();

    /**
     * @param logs   원본 로그 레코드 (수정하지 않음)
     * @param caseId 사건(시나리오) ID — 토큰화 salt. 쓰지 않는 필터는 무시한다.
     */
    FilterResult apply(List<Map<String, Object>> logs, String caseId);

    /**
     * 목적(Purpose Template)을 지정하는 버전. 목적을 쓰는 필터는 [C] 담기뿐이므로
     * 기본 구현은 목적을 무시하고 {@link #apply(List, String)} 를 호출한다.
     */
    default FilterResult apply(List<Map<String, Object>> logs, String caseId, String purposeId) {
        return apply(logs, caseId);
    }
}
