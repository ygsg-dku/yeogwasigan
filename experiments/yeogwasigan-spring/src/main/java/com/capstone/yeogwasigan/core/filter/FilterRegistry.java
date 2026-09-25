package com.capstone.yeogwasigan.core.filter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.capstone.yeogwasigan.core.config.ExperimentConstants;

/**
 * 필터 레지스트리. 웹 데모와 실험 러너는 필터 구현체를 직접 만들지 않고 반드시 여기서 꺼내 쓴다.
 * → 두 경로가 항상 같은 인스턴스(같은 코드·같은 설정)를 호출하게 된다.
 */
@Component
public class FilterRegistry {

    private final Map<String, LogFilter> filters = new LinkedHashMap<>();

    public FilterRegistry(List<LogFilter> all) {
        Map<String, LogFilter> byName = new LinkedHashMap<>();
        for (LogFilter f : all) {
            byName.put(f.name(), f);
        }
        // 등록 순서를 PASSTHROUGH → DENYLIST → ALLOWLIST 로 고정
        for (String name : ExperimentConstants.FILTER_NAMES) {
            LogFilter f = byName.remove(name);
            if (f == null) {
                throw new IllegalStateException("필터 구현체가 없습니다: " + name);
            }
            filters.put(name, f);
        }
        filters.putAll(byName);
    }

    /** 이름으로 필터를 찾는다. 대소문자는 구분하지 않는다 (allowlist == ALLOWLIST). */
    public LogFilter get(String name) {
        LogFilter f = name == null ? null : filters.get(name.trim().toUpperCase(Locale.ROOT));
        if (f == null) {
            throw new IllegalArgumentException("알 수 없는 필터: " + name + " (가능: " + String.join(", ", filters.keySet()) + ")");
        }
        return f;
    }

    public List<LogFilter> all() {
        return List.copyOf(filters.values());
    }
}
