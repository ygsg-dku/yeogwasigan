package com.capstone.yeogwasigan.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.capstone.yeogwasigan.core.config.AppProperties;

/** 응답이 max-tokens 에 걸려 끊기면 답 끝에 표시가 붙는다. 끊긴 답은 결론이 빠질 수 있어 채점할 때 알아야 한다. */
class AiClientTest {

    private static AiClient client(int maxTokens) {
        return new AiClient(new AppProperties(null, null, null,
                new AppProperties.Ai("openai", null, maxTokens, null, true, null, null), null, null));
    }

    @Test
    void 끊긴_답에는_표시가_붙는다() {
        String text = client(4096).markTruncated("근본 원인은", true);
        assertTrue(text.startsWith("근본 원인은"), text);
        assertTrue(text.endsWith("[응답이 길이 상한(max-tokens 4096)에 걸려 여기서 끊겼습니다]"), text);
    }

    @Test
    void 끝까지_온_답은_그대로다() {
        assertEquals("근본 원인은 payment 입니다.", client(4096).markTruncated("근본 원인은 payment 입니다.", false));
    }

    @Test
    void 상한을_비우면_4096() {
        assertEquals(4096, client(0).maxTokens());
    }
}
