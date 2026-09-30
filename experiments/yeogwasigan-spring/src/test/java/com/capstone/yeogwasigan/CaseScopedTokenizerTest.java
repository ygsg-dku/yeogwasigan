package com.capstone.yeogwasigan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.capstone.yeogwasigan.core.tokenize.CaseScopedTokenizer;

class CaseScopedTokenizerTest {

    private final CaseScopedTokenizer tokenizer = new CaseScopedTokenizer("test-secret");
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void 같은_사건_같은_값이면_항상_같은_토큰() {
        assertEquals(tokenizer.tokenize(TRACE, "s01_payment_fail"), tokenizer.tokenize(TRACE, "s01_payment_fail"));
        assertTrue(tokenizer.tokenize(TRACE, "s01_payment_fail").matches("TX_[0-9A-F]{8}"));
    }

    @Test
    void 다른_사건이면_같은_값도_다른_토큰() {
        assertNotEquals(tokenizer.tokenize(TRACE, "s01_payment_fail"), tokenizer.tokenize(TRACE, "s02_other"));
    }

    @Test
    void 파이썬_hmac_구현과_같은_값() {
        // python: "TX_" + hmac.new(b"test-secret", caseId + b"\0" + value, sha256).hexdigest()[:8].upper()
        assertEquals("TX_CB7874F5", tokenizer.tokenize(TRACE, "s01_payment_fail"));
        assertEquals("TX_BE4BB4DD", tokenizer.tokenize(TRACE, "s02_other"));
        assertEquals("PS_30EBAE04", tokenizer.tokenize("otel-demo-node-02", "s01_payment_fail", "PS_"));
        assertEquals("TX_04E8CA36", tokenizer.tokenize("x", ""));
    }

    @Test
    void 비밀키를_모르면_caseId만으로는_토큰을_재현할_수_없다() {
        assertNotEquals(tokenizer.tokenize(TRACE, "s01_payment_fail"),
                new CaseScopedTokenizer("other-secret").tokenize(TRACE, "s01_payment_fail"));
        assertNotEquals(new CaseScopedTokenizer("").tokenize(TRACE, "c"), new CaseScopedTokenizer("").tokenize(TRACE, "c"));
    }
}
