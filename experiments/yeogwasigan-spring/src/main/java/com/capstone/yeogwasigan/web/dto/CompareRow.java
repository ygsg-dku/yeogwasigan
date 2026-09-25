package com.capstone.yeogwasigan.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** POST /api/compare 응답의 한 줄 — 세 방식의 필드 수·잔존 수 요약. 실패한 방식은 error 만 채운다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompareRow(String filter, String filterLabel, Integer fieldsBefore, Integer fieldsAfter,
                         Integer residualCount, String error) {
}
