package com.capstone.yeogwasigan.core.filter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.config.ExperimentConstants;
import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.presidio.PresidioClient;

/**
 * [B] 빼기 (DENYLIST) — 민감정보를 탐지해서 마스킹한 뒤, 나머지는 전부 전송.
 *
 * <h3>원칙</h3>
 * <ul>
 *   <li>Microsoft Presidio 를 "기본 설정 그대로" 사용한다. 탐지기를 직접 짜지 않는다.
 *       (직접 짜면 "일부러 부실하게 만든 것 아니냐"는 반박이 생긴다)</li>
 *   <li>한국어 이름·국내 계좌번호 형식 등을 기본 인식기가 못 잡아도 억지로 보강하지 않는다.
 *       그것이 빼기 방식의 실제 한계이고, 실험에서 측정할 대상이다.</li>
 *   <li>필드는 하나도 버리지 않는다. 모든 필드의 문자열 값을 검사해서 탐지된 부분만 치환한다.
 *       (필드 구조를 건드리지 않는 것이 빼기 방식의 정의)</li>
 *   <li>Presidio 에 연결할 수 없으면 예외를 던진다. 절대 원본을 그대로 내보내지 않는다.</li>
 * </ul>
 *
 * 같은 문자열(예: service.namespace 값)은 한 번의 apply 안에서 한 번만 Presidio 에 보낸다.
 * Presidio 는 같은 입력에 같은 결과를 내므로 결과에는 영향이 없고 호출 수만 줄어든다.
 */
@Component
public class DenylistFilter implements LogFilter {

    private final PresidioClient presidio;
    private final Set<String> skipFields;

    public DenylistFilter(PresidioClient presidio, AppProperties props) {
        this.presidio = presidio;
        this.skipFields = Set.copyOf(props.presidio().skipFields());
    }

    @Override
    public String name() {
        return ExperimentConstants.DENYLIST;
    }

    @Override
    public String label() {
        return "빼기";
    }

    @Override
    public FilterResult apply(List<Map<String, Object>> logs, String caseId) {
        Map<String, PresidioClient.Anonymized> cache = new HashMap<>();
        Map<String, String> transformed = new LinkedHashMap<>();
        Map<String, Integer> entityCounts = new TreeMap<>();

        List<Map<String, Object>> output = new ArrayList<>(logs.size());
        for (Map<String, Object> rec : logs) {
            Map<String, Object> flat = LogFormat.flatten(rec);
            Map<String, Object> scrubbed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : flat.entrySet()) {
                if (skipFields.contains(e.getKey())) {
                    scrubbed.put(e.getKey(), e.getValue());
                    continue;
                }
                scrubbed.put(e.getKey(), LogFormat.mapStrings(e.getValue(), text -> {
                    PresidioClient.Anonymized a = cache.computeIfAbsent(text, presidio::scrub);
                    // 기록용: 실제로 치환된 구간(겹침 해소 후) 기준 유형별 건수, 원본 구간 → 표식
                    a.replacedEntityTypes().forEach(t -> entityCounts.merge(t, 1, Integer::sum));
                    a.detections().forEach(d -> transformed.putIfAbsent(d.original(), "<" + d.entityType() + ">"));
                    return a.text();
                }));
            }
            output.add(LogFormat.unflatten(scrubbed));
        }

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("detectedEntities", entityCounts);
        notes.put("uniqueStringsScanned", cache.size());   // Presidio analyzer 에 보낸 서로 다른 문자열 수
        notes.put("skipFields", skipFields);
        return FilterResult.of(logs, output, transformed, notes);
    }
}
