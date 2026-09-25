package com.capstone.yeogwasigan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 여과시간 — 망분리 환경의 장애 로그를 외부 AI로 보낼 때의 민감정보 여과 방식 비교 실험.
 *
 * <pre>
 * ./gradlew bootRun                                                  # 웹 데모 모드
 * ./gradlew bootRun --args='--spring.profiles.active=experiment'     # 실험 일괄 실행
 * ./gradlew bootRun --args='--spring.profiles.active=blind ...'      # 블라인드 채점 파일 생성/병합
 * </pre>
 *
 * 웹 데모(web/)와 실험 러너(experiment/)는 반드시 core/ 의 같은 필터 구현체를 호출한다.
 * 그래야 실험 결과와 데모 화면이 항상 일치한다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class YeogwasiganApplication {

    public static void main(String[] args) {
        SpringApplication.run(YeogwasiganApplication.class, args);
    }
}
