package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.capstone.yeogwasigan.core.scoring.BlindSetBuilder;

class BlindSetBuilderTest {

    @TempDir
    Path tmp;

    private final BlindSetBuilder builder = new BlindSetBuilder(TestSupport.scenarios());

    @Test
    void 응답의_조건_흔적과_민감값을_가린다() {
        String a = builder.normalizeAnswer(
                "TX_3DCEAB8E 추적 결과 <EMAIL_ADDRESS> 고객 박민지, trace 4bf92f3577b34da6a3ce929d0e0e4736", TestSupport.SAMPLE);
        assertFalse(a.contains("TX_"));
        assertFalse(a.contains("<EMAIL_ADDRESS>"));
        assertFalse(a.contains("박민지"));
        assertFalse(a.contains("4bf92f35"));
        assertTrue(a.contains(BlindSetBuilder.MASK));
    }

    @Test
    void prepare_후_merge_하면_점수가_원래_행으로_돌아간다() throws Exception {
        Path results = tmp.resolve("results.csv");
        Files.writeString(results, "﻿scenario_id,filter,run_no,fields_before,fields_after,residual_pii_count,ai_answer,rca_score\n"
                + "s01_payment_fail,PASSTHROUGH,1,49,49,22,\"줄바꿈\n포함, 쉼표 \"\"따옴표\"\"\",\n"
                + "s01_payment_fail,ALLOWLIST,1,49,9,1,담기 응답,\n", StandardCharsets.UTF_8);

        BlindSetBuilder.PrepareResult p = builder.prepare(results, 42L, true);
        assertEquals(2, p.count());
        String answers = Files.readString(p.answers(), StandardCharsets.UTF_8);
        assertFalse(answers.contains("PASSTHROUGH"));
        assertFalse(answers.contains("ALLOWLIST"));
        assertTrue(Files.readString(p.rubric(), StandardCharsets.UTF_8).contains("| 3 |"));
        assertFalse(Files.readString(p.rubric(), StandardCharsets.UTF_8).contains("affected_filters"));

        // 채점자가 점수를 채웠다고 가정: 담기 응답에 2점, 나머지 3점
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        StringBuilder out = new StringBuilder("\uFEFF");
        try (CSVParser parser = fmt.parse(new StringReader(answers.substring(1)));
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.builder()
                     .setHeader(parser.getHeaderNames().toArray(String[]::new)).build())) {
            for (CSVRecord rec : parser) {
                String score = rec.get("ai_answer").equals("담기 응답") ? "2" : "3";
                printer.printRecord(rec.get("blind_id"), rec.get("scenario_id"), rec.get("ai_answer"), score, "");
            }
        }
        Files.writeString(p.answers(), out.toString(), StandardCharsets.UTF_8);

        BlindSetBuilder.MergeResult m = builder.merge(results);
        String scored = Files.readString(m.scored(), StandardCharsets.UTF_8);
        assertTrue(scored.contains("ALLOWLIST,1,49,9,1,담기 응답,2"), scored);
        assertEquals(0, m.missingScores(), scored);
        assertEquals(2, m.summary().size());
    }
}
