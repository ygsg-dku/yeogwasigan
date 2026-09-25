package com.capstone.yeogwasigan.core.log;

/** 로그 텍스트를 해석할 수 없을 때. 웹에서는 400 으로 응답한다. */
public class LogParseException extends IllegalArgumentException {

    public LogParseException(String message) {
        super(message);
    }
}
