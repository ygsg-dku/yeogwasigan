package com.capstone.yeogwasigan.web.dto;

import com.capstone.yeogwasigan.core.config.ExperimentConstants;

/**
 * POST /api/analyze, /api/filter, /api/compare 요청.
 *
 * @param log     로그 텍스트 (JSONL 또는 OTLP JSON)
 * @param purpose 목적 ID (기본 INCIDENT_ANALYSIS)
 * @param filter  PASSTHROUGH | DENYLIST | ALLOWLIST (대소문자 무관, 기본 ALLOWLIST)
 * @param caseId  토큰화 salt(사건 단위). 같은 caseId 면 같은 값 → 같은 토큰. 기본 "demo"
 * @param preprocess true 면 필터 전에 실험과 같은 공통 전처리(①②)를 적용한다. 원본 수집 로그를 그대로 올릴 때용. 기본 false
 */
public record AnalyzeRequest(String log, String purpose, String filter, String caseId, boolean preprocess) {

    public AnalyzeRequest {
        purpose = purpose == null || purpose.isBlank() ? ExperimentConstants.PURPOSE_ID : purpose.trim();
        filter = filter == null || filter.isBlank() ? ExperimentConstants.ALLOWLIST : filter.trim();
        caseId = caseId == null || caseId.isBlank() ? "demo" : caseId.trim();
    }

    public AnalyzeRequest withFilter(String newFilter) {
        return new AnalyzeRequest(log, purpose, newFilter, caseId, preprocess);
    }
}
