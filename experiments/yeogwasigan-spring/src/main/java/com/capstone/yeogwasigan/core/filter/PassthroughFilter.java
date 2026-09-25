package com.capstone.yeogwasigan.core.filter;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.capstone.yeogwasigan.core.config.ExperimentConstants;
import com.capstone.yeogwasigan.core.log.LogFormat;

/** [A] 그대로 전송 (PASSTHROUGH) — 아무 처리 없이 전송. 비교 기준선. */
@Component
public class PassthroughFilter implements LogFilter {

    @Override
    public String name() {
        return ExperimentConstants.PASSTHROUGH;
    }

    @Override
    public String label() {
        return "그대로";
    }

    @Override
    public FilterResult apply(List<Map<String, Object>> logs, String caseId) {
        return FilterResult.of(logs, LogFormat.deepCopy(logs), Map.of(), Map.of());
    }
}
