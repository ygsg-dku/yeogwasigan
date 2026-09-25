package com.capstone.yeogwasigan.core.scenario;

/**
 * injected_pii.yaml 의 항목 하나 — 시나리오에 일부러 주입한 민감값.
 *
 * @param id          예) PII-01
 * @param type        예) ACCOUNT_NUMBER, EMAIL, PERSON_NAME, INTERNAL_IP
 * @param value       결과물에서 그대로 검색할 원본 문자열 (채점에는 이 값만 쓰인다)
 * @param description 설명
 */
public record PiiItem(String id, String type, String value, String description) {
}
