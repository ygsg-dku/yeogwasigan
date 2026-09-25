package com.capstone.yeogwasigan.experiment;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.scoring.BlindSetBuilder;

/**
 * 블라인드 채점 (blind 프로파일).
 *
 * <pre>
 * # ① 채점용 파일 만들기: A/B/C 라벨 제거 + 무작위 섞기
 * ./gradlew bootRun --args='--spring.profiles.active=blind --yeogwasigan.blind.action=prepare --yeogwasigan.blind.results=results/&lt;실행시각&gt;/results.csv'
 *
 * # ② 채점자가 blind/blind_answers.csv 의 rca_score 칸을 채운다 (기준: blind/rubric.md)
 *
 * # ③ 점수를 results.csv 에 합치고 방식별 평균 출력
 * ./gradlew bootRun --args='--spring.profiles.active=blind --yeogwasigan.blind.action=merge --yeogwasigan.blind.results=results/&lt;실행시각&gt;/results.csv'
 * </pre>
 */
@Component
@Profile("blind")
public class BlindRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BlindRunner.class);

    private final BlindSetBuilder builder;
    private final AppProperties props;

    public BlindRunner(BlindSetBuilder builder, AppProperties props) {
        this.builder = builder;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        AppProperties.Blind blind = props.blind();
        if (blind.results() == null || blind.results().isBlank()) {
            throw new IllegalArgumentException("--yeogwasigan.blind.results=results/<실행시각>/results.csv 를 지정하세요.");
        }
        Path results = Path.of(blind.results());
        if (!Files.isRegularFile(results)) {
            throw new IllegalArgumentException("results.csv 를 찾을 수 없습니다: " + results.toAbsolutePath());
        }

        switch (blind.action()) {
            case "prepare" -> {
                BlindSetBuilder.PrepareResult r = builder.prepare(results, blind.seed(), blind.normalize());
                log.info("채점용 파일({}건): {}", r.count(), r.answers());
                log.info("채점 기준: {}", r.rubric());
                log.info("매핑표(채점 끝날 때까지 열지 말 것): {}", r.key());
            }
            case "merge" -> {
                BlindSetBuilder.MergeResult r = builder.merge(results);
                StringBuilder sb = new StringBuilder("\n");
                sb.append(String.format("%-12s %4s %6s %16s %10s%n", "filter", "n", "채점", "평균 rca_score", "평균 잔존"));
                for (BlindSetBuilder.FilterSummary s : r.summary()) {
                    sb.append(String.format("%-12s %4d %6d %16.2f %10.2f%n",
                            s.filter(), s.n(), s.scored(), s.meanRcaScore(), s.meanResidual()));
                }
                log.info(sb.toString());
                if (r.missingScores() > 0) {
                    log.warn("rca_score 가 비어 있는 행이 {}개 있습니다.", r.missingScores());
                }
                log.info("병합 결과: {}", r.scored());
            }
            default -> throw new IllegalArgumentException("yeogwasigan.blind.action 은 prepare 또는 merge 여야 합니다: " + blind.action());
        }
    }
}
