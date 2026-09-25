package com.capstone.yeogwasigan.core.template;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * classpath:templates/*.yaml 의 Purpose Template 을 읽는다.
 *
 * <p>YAML 에 모르는 키가 있거나 fieldAction 이름이 틀리면 서버 시작 시점에 바로 실패한다.
 * (오타가 조용히 무시되어 엉뚱한 필드가 나가는 일을 막기 위해)
 */
@Component
public class TemplateLoader {

    private static final String LOCATION = "classpath*:templates/*.yaml";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Map<String, PurposeTemplate> templates;

    /** Spring 이 사용하는 생성자: classpath 에서 템플릿을 모두 읽는다. */
    public TemplateLoader() {
        this(loadFromClasspath());
    }

    private TemplateLoader(Collection<PurposeTemplate> list) {
        Map<String, PurposeTemplate> map = new TreeMap<>();
        for (PurposeTemplate t : list) {
            if (map.put(t.purposeId(), t) != null) {
                throw new IllegalStateException("purposeId 가 중복된 템플릿이 있습니다: " + t.purposeId());
            }
        }
        this.templates = map;
    }

    /** 테스트 등에서 템플릿을 직접 지정할 때. */
    public static TemplateLoader of(Collection<PurposeTemplate> list) {
        return new TemplateLoader(list);
    }

    public PurposeTemplate get(String purposeId) {
        PurposeTemplate t = templates.get(purposeId);
        if (t == null) {
            throw new IllegalArgumentException("알 수 없는 목적(purpose): " + purposeId + " (가능: " + templates.keySet() + ")");
        }
        return t;
    }

    public List<PurposeTemplate> list() {
        return new ArrayList<>(templates.values());
    }

    /** YAML 하나를 읽어 템플릿으로 변환한다. */
    public static PurposeTemplate parse(InputStream in) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object loaded = yaml.load(in);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Purpose Template YAML 의 최상위는 객체여야 합니다.");
        }
        return MAPPER.convertValue(new LinkedHashMap<>(map), PurposeTemplate.class);
    }

    /** 템플릿 원본(YAML 그대로의 Map). run_meta.json 기록용. */
    public Map<String, Object> describe(String purposeId) {
        PurposeTemplate t = get(purposeId);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = MAPPER.convertValue(t, LinkedHashMap.class);
        return m;
    }

    private static List<PurposeTemplate> loadFromClasspath() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
            List<PurposeTemplate> list = new ArrayList<>();
            for (Resource r : resources) {
                try (InputStream in = r.getInputStream()) {
                    list.add(parse(in));
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Purpose Template 을 읽을 수 없습니다: " + r.getFilename() + " — " + e.getMessage(), e);
                }
            }
            if (list.isEmpty()) {
                throw new IllegalStateException("Purpose Template 이 하나도 없습니다: " + LOCATION);
            }
            return list;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
