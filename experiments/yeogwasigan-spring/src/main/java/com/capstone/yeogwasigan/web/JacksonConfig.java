package com.capstone.yeogwasigan.web;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.StreamReadConstraints;

/**
 * 웹 요청 본문의 문자열 길이 제한을 늘린다.
 *
 * <p>Jackson 기본값은 문자열 하나당 2천만 자다. 원본 수집 로그(S4 약 2,400만 자, S5 약 4,800만 자)를
 * 파일 업로드로 통째로 보내면 {"log": "..."} 의 log 값이 이 제한에 걸려 400 오류가 난다.
 */
@Configuration
public class JacksonConfig {

    static final int MAX_STRING_LENGTH = 200_000_000;

    @Bean
    Jackson2ObjectMapperBuilderCustomizer largeLogStrings() {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder().maxStringLength(MAX_STRING_LENGTH).build()));
    }
}
