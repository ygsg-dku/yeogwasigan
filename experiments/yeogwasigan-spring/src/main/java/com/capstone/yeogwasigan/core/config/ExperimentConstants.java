package com.capstone.yeogwasigan.core.config;

import java.util.List;

/**
 * 실험 전체에서 고정되는 값.
 *
 * <p>세 조건(A/B/C)은 반드시 아래 값을 똑같이 사용한다. 설정 파일이 아니라 코드 상수로 둔 이유는
 * 실행 옵션 하나로 실수로 바뀌는 일을 막기 위해서다. 값을 바꾸면 실험 조건이 바뀌는 것이므로,
 * 바꿀 때는 results/ 의 run_meta.json 에 기록되는 값을 함께 확인할 것.
 */
public final class ExperimentConstants {

    private ExperimentConstants() {
    }

    /** 외부 AI 호출 목적은 하나로 고정: 장애 근본원인 분석. */
    public static final String PURPOSE_ID = "INCIDENT_ANALYSIS";

    /** 세 조건 모두 완전히 동일한 질문 (한 글자도 바꾸지 말 것). */
    public static final String FIXED_QUESTION =
            "다음 로그를 분석해서, 이 장애의 근본 원인이 무엇인지와 그렇게 판단한 근거를 설명해주세요.";

    /** temperature 고정. */
    public static final double TEMPERATURE = 0.0;

    /**
     * OpenAI 추론 모델(gpt-5.x 등)의 reasoning_effort 고정값.
     * gpt-5.1 은 "none" 일 때만 temperature 0 을 받는다("low" 이상이면 400 오류). 추론 모델이 아니면 보내지 않는다.
     */
    public static final String REASONING_EFFORT = "none";

    /** 필터 이름 (실행 순서 = 결과 CSV 순서). */
    public static final String PASSTHROUGH = "PASSTHROUGH";
    public static final String DENYLIST = "DENYLIST";
    public static final String ALLOWLIST = "ALLOWLIST";
    public static final List<String> FILTER_NAMES = List.of(PASSTHROUGH, DENYLIST, ALLOWLIST);
}
