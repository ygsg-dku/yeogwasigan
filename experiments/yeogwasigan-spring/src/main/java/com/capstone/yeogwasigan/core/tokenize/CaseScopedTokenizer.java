package com.capstone.yeogwasigan.core.tokenize;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * 사건(case) 단위 토큰화.
 *
 * <pre>
 * token = "TX_" + HMAC-SHA256(원본값, key=caseId) 앞 8자리 대문자
 * </pre>
 *
 * <ul>
 *   <li>salt(key)를 시나리오(사건) 단위로 스코프한다.</li>
 *   <li>같은 시나리오 안에서는 같은 값 → 항상 같은 토큰 (AI 가 traceId/spanId 관계를 그대로 추적할 수 있다)</li>
 *   <li>다른 시나리오에서는 같은 값이라도 다른 토큰 (사건끼리 맞춰 보는 식의 재식별을 차단한다)</li>
 * </ul>
 *
 * 접두어는 용도에 따라 다르다: TX_(식별자 토큰), PS_(자산 가명), EMAIL_/ACCT_/IP_ ...(본문 내부 재검사).
 */
@Component
public class CaseScopedTokenizer {

    public static final String DEFAULT_PREFIX = "TX_";

    private static final String ALGORITHM = "HmacSHA256";

    /** 기본 접두어(TX_)로 토큰화한다. */
    public String tokenize(Object value, String caseId) {
        return tokenize(value, caseId, DEFAULT_PREFIX);
    }

    public String tokenize(Object value, String caseId, String prefix) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(keyBytes(caseId), ALGORITHM));
            byte[] digest = mac.doFinal(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(digest).substring(0, 8).toUpperCase(Locale.ROOT);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 을 사용할 수 없습니다.", e);
        }
    }

    /**
     * HMAC 규격상 빈 키는 블록 크기만큼 0으로 채운 키와 같다. 자바의 SecretKeySpec 은 빈 배열을 거부하므로
     * 같은 결과를 내는 1바이트 0 키로 대신한다. (caseId 가 비어 있어도 동작은 하지만, 가능하면 항상 지정할 것)
     */
    private static byte[] keyBytes(String caseId) {
        byte[] key = (caseId == null ? "" : caseId).getBytes(StandardCharsets.UTF_8);
        return key.length == 0 ? new byte[] {0} : key;
    }
}
