package com.capstone.yeogwasigan.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.capstone.yeogwasigan.gateway.WarningRules.Finding;
import com.capstone.yeogwasigan.gateway.WarningRules.Level;

/** 경고 기준 v0.1 의 등급·가림·우선순위·검사 범위. 값별 기대치는 {@link SensitiveValueFixtureTest}. */
class WarningRulesTest {

    private static List<Finding> scan(String body) {
        return WarningRules.scan(List.of(Map.of("logRecord", Map.of("body", body))));
    }

    @Test
    void 등급은_높음과_낮음으로_나뉜다() {
        for (String t : List.of("ID", "EMAIL", "RRN", "CARD", "PHONE", "ACCOUNT")) {
            assertEquals(Level.HIGH, WarningRules.levelOf(t), t);
        }
        assertEquals(Level.LOW, WarningRules.levelOf("UUID"));
        assertEquals(Level.LOW, WarningRules.levelOf("IPV4"));
    }

    @Test
    void 화면에_보내는_값은_가린다() {
        assertEquals("k***@bank.com", WarningRules.mask("EMAIL", "kim@bank.com"));
        assertEquals("****-****-****-1111", WarningRules.mask("CARD", "4111 1111 1111 1111"));
        assertEquals("900101-*******", WarningRules.mask("RRN", "900101-1234567"));
        assertEquals("010-****-5678", WarningRules.mask("PHONE", "010-1234-5678"));
        assertEquals("*********789", WarningRules.mask("ACCOUNT", "110-123-456789"));
        assertEquals("3f2a****", WarningRules.mask("ID", "3f2a9c1e-4b7d-4e2a-9c1e-8f3a2b1c0d9e"));
        assertEquals("8.8.*.*", WarningRules.mask("IPV4", "8.8.4.4"));
    }

    @Test
    void 먼저_잡힌_자리는_다시_세지_않는다() {
        List<Finding> f = scan("user.id=3f2a9c1e-4b7d-4e2a-9c1e-8f3a2b1c0d9e card 4111-1111-1111-1111");
        assertEquals(List.of("ID", "CARD"), f.stream().map(Finding::type).toList(), "UUID·계좌로 다시 세지 않는다");
    }

    @Test
    void 위치와_칸을_알려준다() {
        List<Finding> f = WarningRules.scan(List.of(
                Map.of("logRecord", Map.of("body", "ok")),
                Map.of("logRecord", Map.of("attributes", Map.of("exception.message", "mail kim@bank.com")))));
        assertEquals(1, f.size());
        assertEquals(1, f.get(0).record());
        assertEquals("logRecord.attributes.exception.message", f.get(0).field());
        assertEquals("kim@bank.com", "mail kim@bank.com".substring(f.get(0).start(), f.get(0).end()));
    }

    @Test
    void 시각_trace_버전_칸은_검사하지_않는다() {
        List<Finding> f = WarningRules.scan(List.of(Map.of("logRecord", Map.of(
                "timeUnixNano", "4111111111111111",
                "traceId", "kim@bank.com",
                "scope", Map.of("version", "8.8.4.4")))));
        assertTrue(f.isEmpty(), f.toString());
    }

    @Test
    void 카드_검증은_번호대와_Luhn을_본다() {
        assertTrue(WarningRules.isCard("4111111111111111"));
        assertFalse(WarningRules.isCard("4111111111111112"), "Luhn 실패");
        assertFalse(WarningRules.isCard("1790154142736"), "1로 시작하는 13자리는 밀리초 시각");
    }

    @Test
    void 사설_루프백_네트워크_주소는_공인_IP가_아니다() {
        for (String ip : List.of("10.0.3.21", "172.18.0.5", "192.168.0.1", "127.0.0.1", "149.0.0.0", "8.8.8.255", "01.2.3.4")) {
            assertFalse(WarningRules.isPublicHostIp(ip), ip);
        }
        assertTrue(WarningRules.isPublicHostIp("8.8.4.4"));
        assertTrue(WarningRules.isPublicHostIp("172.32.0.1"), "172.16~31 만 사설");
    }
}
