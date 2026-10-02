package com.capstone.yeogwasigan.core.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.capstone.yeogwasigan.core.config.AppProperties;
import com.capstone.yeogwasigan.core.log.LogFormat;

/**
 * scenarios/ 폴더를 읽는다.
 *
 * <pre>
 * scenarios/&lt;id&gt;/raw.jsonl           원본 로그 (필수)
 * scenarios/&lt;id&gt;/injected_pii.yaml   주입한 민감값 (없으면 잔존 0으로 계산)
 * scenarios/&lt;id&gt;/truth.yaml          정답·채점 기준 (AI 에는 절대 보내지 않는다)
 * </pre>
 *
 * 매번 디스크에서 새로 읽으므로 서버를 켠 채로 시나리오를 고쳐도 바로 반영된다.
 */
@Component
public class ScenarioRepository {

    /** 폴더 이름으로 쓸 수 있는 ID (경로 조작 방지). */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private final Path root;

    @Autowired
    public ScenarioRepository(AppProperties props) {
        this(Path.of(props.scenariosDir()));
    }

    /** 테스트 등에서 폴더를 직접 지정할 때. */
    public ScenarioRepository(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** raw.jsonl 이 있는 시나리오 ID 목록 (이름순). "." 또는 "_" 로 시작하는 폴더는 건너뛴다. */
    public List<String> ids() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith(".") && !n.startsWith("_"))
                    .filter(n -> SAFE_ID.matcher(n).matches())
                    .filter(n -> Files.isRegularFile(root.resolve(n).resolve("raw.jsonl")))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Scenario get(String id) {
        Path dir = dirOf(id);
        List<Map<String, Object>> raw = LogFormat.parse(rawText(id));
        return new Scenario(id, raw, loadPii(dir.resolve("injected_pii.yaml")), dir);
    }

    /** raw.jsonl 원문 (데모 화면의 [샘플 불러오기]). */
    public String rawText(String id) {
        return read(dirOf(id).resolve("raw.jsonl"));
    }

    /** 모든 시나리오의 민감값 합집합 (값 기준 중복 제거). 데모에서 사용자가 로그를 직접 고칠 수 있으므로 이 기준을 쓴다. */
    public List<PiiItem> allPii() {
        Map<String, PiiItem> byValue = new LinkedHashMap<>();
        for (String id : ids()) {
            for (PiiItem item : loadPii(dirOf(id).resolve("injected_pii.yaml"))) {
                byValue.putIfAbsent(item.value(), item);
            }
        }
        return new ArrayList<>(byValue.values());
    }

    /** 시나리오의 민감값 문자열 (긴 것부터). 블라인드 채점에서 응답 안의 민감값을 가릴 때 쓴다. */
    public List<String> piiValues(String id) {
        if (!exists(id)) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        loadPii(dirOf(id).resolve("injected_pii.yaml")).forEach(p -> values.add(p.value()));
        List<String> out = new ArrayList<>(values);
        out.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return out;
    }

    /** truth.yaml (없으면 빈 Map). */
    public Map<String, Object> truth(String id) {
        Path p = dirOf(id).resolve("truth.yaml");
        return Files.isRegularFile(p) ? yamlMap(p) : Map.of();
    }

    public boolean exists(String id) {
        return id != null && SAFE_ID.matcher(id).matches() && Files.isRegularFile(root.resolve(id).resolve("raw.jsonl"));
    }

    private Path dirOf(String id) {
        if (!exists(id)) {
            throw new IllegalArgumentException("시나리오를 찾을 수 없습니다: " + id);
        }
        return root.resolve(id);
    }

    @SuppressWarnings("unchecked")
    static List<PiiItem> loadPii(Path path) {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        Object items = yamlMap(path).get("items");
        if (!(items instanceof List<?> list)) {
            return List.of();
        }
        List<PiiItem> out = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> m = (Map<String, Object>) o;
            Object value = m.get("value");
            if (value == null || String.valueOf(value).isEmpty()) {
                continue;
            }
            out.add(new PiiItem(str(m.get("id")), str(m.get("type")), String.valueOf(value), str(m.get("description"))));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yamlMap(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            return loaded instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
