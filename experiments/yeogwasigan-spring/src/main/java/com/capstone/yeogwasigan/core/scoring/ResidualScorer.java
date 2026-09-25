package com.capstone.yeogwasigan.core.scoring;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.capstone.yeogwasigan.core.log.LogFormat;
import com.capstone.yeogwasigan.core.scenario.PiiItem;

/**
 * 민감값 잔존 수 계산.
 *
 * <p>injected_pii.yaml 에 기록된 "주입한 민감값"이 필터 결과물에 몇 번 남아 있는지 <b>단순 문자열 검색</b>으로 센다.
 * 탐지기(Presidio, 정규식)의 판단을 전혀 쓰지 않으므로 어느 필터에도 유리하지 않은 객관적 채점이 된다.
 *
 * <p>검색 대상 텍스트는 {@link LogFormat#serialize} — AI 프롬프트에 들어가는 것과 똑같은 직렬화 결과다.
 * 완전한 원문 문자열만 센다. (예: 계좌번호가 "110-&lt;US_SSN&gt;" 처럼 일부만 남으면 0건)
 */
@Component
public class ResidualScorer {

    /** 값별 잔존 횟수. */
    public record Hit(String id, String type, String value, int count) {
    }

    /**
     * @param total   잔존 총 횟수
     * @param detail  값별 잔존 횟수 (count &gt; 0 인 것만)
     * @param checked 검사한 민감값 종류 수
     */
    public record ResidualResult(int total, List<Hit> detail, int checked) {
    }

    /** 잔존 총 횟수만. */
    public int count(List<Map<String, Object>> output, List<PiiItem> injectedPii) {
        return score(output, injectedPii).total();
    }

    public ResidualResult score(List<Map<String, Object>> output, List<PiiItem> injectedPii) {
        String text = LogFormat.serialize(output);
        List<Hit> detail = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (PiiItem item : injectedPii) {
            String value = item.value();
            if (value == null || value.isEmpty() || !seen.add(value)) {
                continue;   // 같은 값이 두 번 등록돼도 한 번만 센다
            }
            int n = countOccurrences(text, value);
            if (n > 0) {
                detail.add(new Hit(item.id(), item.type(), value, n));
                total += n;
            }
        }
        return new ResidualResult(total, detail, seen.size());
    }

    /** 겹치지 않는 등장 횟수 (파이썬 str.count 와 같은 기준). */
    static int countOccurrences(String text, String value) {
        int n = 0;
        int idx = text.indexOf(value);
        while (idx >= 0) {
            n++;
            idx = text.indexOf(value, idx + value.length());
        }
        return n;
    }
}
