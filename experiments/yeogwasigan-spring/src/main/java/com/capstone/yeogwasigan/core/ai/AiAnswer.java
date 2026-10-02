package com.capstone.yeogwasigan.core.ai;

/**
 * 외부 AI 응답.
 *
 * @param text        응답 본문 (오류 시 "[AI 호출 오류] ..." 문자열)
 * @param mode        live | mock | error — mock 응답은 실험 결과로 쓰면 안 된다
 * @param provider    anthropic | openai
 * @param model       모델명
 * @param temperature 항상 0
 * @param promptChars 프롬프트 길이(문자 수)
 */
public record AiAnswer(String text, String mode, String provider, String model, double temperature, int promptChars) {
}
