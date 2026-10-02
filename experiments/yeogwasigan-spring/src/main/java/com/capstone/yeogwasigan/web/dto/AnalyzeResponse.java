package com.capstone.yeogwasigan.web.dto;

import java.util.List;
import java.util.Map;

import com.capstone.yeogwasigan.core.scoring.KeywordScorer;
import com.capstone.yeogwasigan.core.scoring.ResidualScorer;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * POST /api/analyze (및 /api/filter) 응답.
 *
 * <p>FilterResult.transformed(원본값 → 변환값)는 원본 민감값이 들어 있으므로 싣지 않는다.
 * aiAnswer/aiMode/aiModel/autoScore 는 /api/analyze 에서만 채워진다. autoScore 는 채점 시나리오를 고른 경우만.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalyzeResponse(
        String filter,
        String filterLabel,
        List<Map<String, Object>> output,
        int fieldsBefore,
        int fieldsAfter,
        List<String> droppedFields,
        int residualCount,
        List<ResidualScorer.Hit> residualDetail,
        Map<String, Object> notes,
        String aiAnswer,
        String aiMode,
        String aiModel,
        KeywordScorer.Score autoScore) {

    public AnalyzeResponse withAi(String answer, String mode, String model, KeywordScorer.Score score) {
        return new AnalyzeResponse(filter, filterLabel, output, fieldsBefore, fieldsAfter, droppedFields,
                residualCount, residualDetail, notes, answer, mode, model, score);
    }
}
