package com.capstone.yeogwasigan.core.template;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Purpose Template (src/main/resources/templates/*.yaml) 매핑 객체.
 *
 * <p>[C] 담기의 규칙 전부가 여기에 들어 있다. 코드에는 "무엇을 담을지"가 하나도 하드코딩되어 있지 않다.
 *
 * @param purposeId             목적 ID (예: INCIDENT_ANALYSIS)
 * @param displayName           화면 표시 이름
 * @param description           설명
 * @param requiredFields        원문 그대로(단, 내부 재검사 후) 담는 필드
 * @param fieldActions          담되 값을 바꿔서 내보내는 필드 → 변환 방식
 * @param onUnknownField        목록에 없는 필드 처리 (DROP 만 지원)
 * @param innerScanOnKeptFields 원문 그대로 담은 필드 내부를 한 번 더 검사할지
 * @param innerScanEngine       내부 재검사 엔진 (REGEX | PRESIDIO)
 */
public record PurposeTemplate(
        String purposeId,
        String displayName,
        String description,
        List<String> requiredFields,
        Map<String, FieldAction> fieldActions,
        UnknownFieldPolicy onUnknownField,
        Boolean innerScanOnKeptFields,
        InnerScanEngine innerScanEngine) {

    public PurposeTemplate {
        if (purposeId == null || purposeId.isBlank()) {
            throw new IllegalArgumentException("Purpose Template 에 purposeId 가 없습니다.");
        }
        displayName = displayName == null || displayName.isBlank() ? purposeId : displayName;
        description = description == null ? "" : description;
        requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
        fieldActions = fieldActions == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fieldActions));
        onUnknownField = onUnknownField == null ? UnknownFieldPolicy.DROP : onUnknownField;
        innerScanOnKeptFields = innerScanOnKeptFields == null || innerScanOnKeptFields;
        innerScanEngine = innerScanEngine == null ? InnerScanEngine.REGEX : innerScanEngine;
    }

    /**
     * 담을 수 있는 필드 = requiredFields ∪ fieldActions 의 키.
     * fieldActions 에 적힌 필드는 "변환해서 담는다"는 뜻이므로 requiredFields 에 없어도 담긴다.
     */
    public Set<String> allowedFields() {
        Set<String> allowed = new LinkedHashSet<>(requiredFields);
        allowed.addAll(fieldActions.keySet());
        return allowed;
    }

    /** 담되 값을 바꾸는 방식. 둘 다 사건(case) 단위 HMAC 토큰이고 접두어만 다르다. */
    public enum FieldAction {
        /** 식별자(traceId, spanId 등) → TX_xxxxxxxx. 같은 사건 안에서 관계 추적이 가능하다. */
        TOKENIZE_CASE_SCOPED("TX_"),
        /** 자산 이름(host.name, container.id 등) → PS_xxxxxxxx. */
        PSEUDONYMIZE_CASE_SCOPED("PS_");

        private final String prefix;

        FieldAction(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    /** 목록에 없는 필드 처리. 담기의 정의상 DROP 만 지원한다. */
    public enum UnknownFieldPolicy {
        DROP
    }

    /** 담은 필드 내부 재검사 엔진. */
    public enum InnerScanEngine {
        /** AllowlistFilter 의 정규식 (원안). */
        REGEX,
        /** [B] 빼기와 똑같은 Presidio 기본 엔진 — "담기의 이득이 필드 선택 덕분인가, 정규식 덕분인가" 분리 검증용. */
        PRESIDIO
    }
}
