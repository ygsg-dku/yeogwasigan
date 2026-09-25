package com.capstone.yeogwasigan;

import java.nio.file.Path;
import java.util.List;

import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.filter.AllowlistFilter;
import com.capstone.yeogwasigan.core.filter.PassthroughFilter;
import com.capstone.yeogwasigan.core.presidio.PresidioClient;
import com.capstone.yeogwasigan.core.scenario.ScenarioRepository;
import com.capstone.yeogwasigan.core.template.TemplateLoader;
import com.capstone.yeogwasigan.core.tokenize.CaseScopedTokenizer;

/** 스프링 컨텍스트 없이 core 객체를 조립한다. (Presidio·AI 컨테이너 없이 돌아가는 테스트용) */
public final class TestSupport {

    private TestSupport() {
    }

    public static final String SAMPLE = "s01_payment_fail";

    public static AppProperties props() {
        return new AppProperties(null, null, null, null, null, null);
    }

    public static ScenarioRepository scenarios() {
        return new ScenarioRepository(Path.of("scenarios"));
    }

    public static TemplateLoader templates() {
        try (var in = TestSupport.class.getResourceAsStream("/templates/incident_analysis.yaml")) {
            return TemplateLoader.of(List.of(TemplateLoader.parse(in)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static AllowlistFilter allowlist() {
        return new AllowlistFilter(templates(), new CaseScopedTokenizer(), new PresidioClient(props()));
    }

    public static PassthroughFilter passthrough() {
        return new PassthroughFilter();
    }
}
