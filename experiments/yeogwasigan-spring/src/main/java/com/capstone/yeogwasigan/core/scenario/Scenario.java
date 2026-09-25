package com.capstone.yeogwasigan.core.scenario;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 실험 시나리오 하나 (scenarios/&lt;id&gt;/).
 *
 * @param id          시나리오 ID = 폴더 이름. 토큰화 salt(caseId)로도 쓰인다
 * @param raw         raw.jsonl 을 파싱한 원본 레코드
 * @param injectedPii injected_pii.yaml 의 민감값 목록
 * @param dir         시나리오 폴더
 */
public record Scenario(String id, List<Map<String, Object>> raw, List<PiiItem> injectedPii, Path dir) {
}
