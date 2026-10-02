package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.capstone.yeogwasigan.core.scoring.KeywordScorer;

class KeywordScorerTest {

    @TempDir
    Path dir;

    private KeywordScorer scorer() throws IOException {
        Path f = dir.resolve("scenarios.json");
        Files.writeString(f, """
                {"_meta": {"committed_by": "D"},
                 "S3_productCatalogLocked": {"description": "상품 DB 잠김",
                   "service_hit_keywords": {"ko": ["상품", "카탈로그"], "en": ["product", "catalog"]},
                   "cause_hit_keywords": {"ko": ["락"], "en": ["lock", "contention"]}}}
                """);
        return new KeywordScorer(f);
    }

    @Test
    void 폴더_이름이_달라도_접두어로_규칙을_찾는다() throws IOException {
        KeywordScorer s = scorer();
        assertTrue(s.rule("S3_productCatalogLockContention").isPresent());
        assertTrue(s.rule("S3").isPresent());
        assertFalse(s.rule("S0_normal").isPresent());
        assertEquals("D", s.meta().get("committed_by"));
    }

    @Test
    void 키워드가_있으면_적중이고_대소문자는_무시한다() throws IOException {
        KeywordScorer.Score sc = scorer().score("S3_productCatalogLockContention",
                "근본 원인은 Product-Catalog 서비스의 DB LOCK 경합입니다.").orElseThrow();
        assertTrue(sc.serviceHit());
        assertTrue(sc.causeHit());
        assertEquals(List.of("product", "catalog"), sc.serviceMatched());
        assertEquals(List.of("lock"), sc.causeMatched());
    }

    @Test
    void 서비스만_맞히고_원인은_틀릴_수_있다() throws IOException {
        KeywordScorer.Score sc = scorer().score("S3", "상품 조회가 네트워크 문제로 느려졌습니다.").orElseThrow();
        assertTrue(sc.serviceHit());
        assertFalse(sc.causeHit());
    }

    @Test
    void 기준_파일이_없으면_채점하지_않는다() {
        KeywordScorer s = new KeywordScorer(dir.resolve("없음.json"));
        assertFalse(s.available());
        assertTrue(s.score("S1_paymentFailure", "payment failed").isEmpty());
    }
}
