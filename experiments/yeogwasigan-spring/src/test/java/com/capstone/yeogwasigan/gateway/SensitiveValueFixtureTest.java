package com.capstone.yeogwasigan.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.capstone.yeogwasigan.gateway.GatewayRequest.Warning;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 민감값 탐지 fixture({@code fixtures/sensitive-values.json})로 참고 경고 기준을 시험한다.
 * 경고 기준을 바꾸면 fixture 의 expect 도 같이 고친다. note 가 붙은 항목은 현재 기준의 알려진 오탐·미탐이다.
 */
class SensitiveValueFixtureTest {

    @TestFactory
    Stream<DynamicTest> fixture_대로_경고가_뜬다() throws IOException {
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/sensitive-values.json")) {
            cases = new ObjectMapper().readTree(in).get("cases");
        }
        assertFalse(cases.isEmpty(), "fixture 가 비어 있다");
        return Stream.iterate(0, i -> i < cases.size(), i -> i + 1).map(cases::get).map(c -> DynamicTest.dynamicTest(
                c.get("name").asText(), () -> {
                    Map<String, Integer> expect = new LinkedHashMap<>();
                    c.get("expect").properties().forEach(e -> expect.put(e.getKey(), e.getValue().asInt()));
                    List<Warning> got = GatewayService.warnings(List.of(Map.of(c.get("field").asText(), c.get("value").asText())));
                    Map<String, Integer> actual = new LinkedHashMap<>();
                    got.forEach(w -> actual.put(w.type(), w.count()));
                    assertEquals(expect, actual, c.get("value").asText());
                }));
    }
}
