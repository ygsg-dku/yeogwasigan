package com.capstone.yeogwasigan.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.capstone.yeogwasigan.core.presidio.PresidioUnavailableException;

/** 오류를 {"detail": "..."} 형식으로 돌려준다. 화면은 detail 을 그대로 보여준다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 로그 형식 오류, 알 수 없는 필터·목적·시나리오 → 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", "요청 본문(JSON)을 읽을 수 없습니다."));
    }

    /** 게이트웨이: 401 사용자 아님, 403 권한 없음·자기 요청, 404 요청 없음, 409 승인 대기가 아님·파생본 다름 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("detail", String.valueOf(e.getReason())));
    }

    /** Presidio 컨테이너가 없거나 아직 준비 중 → 503 (빼기 결과를 원본으로 대체하지 않는다) */
    @ExceptionHandler(PresidioUnavailableException.class)
    public ResponseEntity<Map<String, String>> presidio(PresidioUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("detail", String.valueOf(e.getMessage())));
    }
}
