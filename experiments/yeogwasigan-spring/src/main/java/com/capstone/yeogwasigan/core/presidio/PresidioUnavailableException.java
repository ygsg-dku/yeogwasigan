package com.capstone.yeogwasigan.core.presidio;

/**
 * Presidio 컨테이너에 연결할 수 없거나 오류가 났을 때.
 *
 * <p>이 경우 [B] 빼기는 절대 "그대로 전송"으로 대체하지 않고 실패시킨다.
 * (마스킹 안 된 로그가 빼기 결과인 것처럼 나가면 실험도, 데모도 틀어진다)
 */
public class PresidioUnavailableException extends RuntimeException {

    public PresidioUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
