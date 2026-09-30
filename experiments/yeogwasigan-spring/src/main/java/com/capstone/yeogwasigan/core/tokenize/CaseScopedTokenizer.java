package com.capstone.yeogwasigan.core.tokenize;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 사건(case) 단위 토큰화.
 *
 * <pre>
 * token = 접두어 + HMAC-SHA256(key=서버 비밀키, msg=caseId 0x00 원본값) 앞 8자리 대문자
 * </pre>
 *
 * <ul>
 *   <li>같은 사건 안에서는 같은 값 → 항상 같은 토큰 (AI 가 traceId/spanId 관계를 그대로 추적할 수 있다)</li>
 *   <li>다른 사건에서는 같은 값이라도 다른 토큰 (사건끼리 맞춰 보는 식의 재식별을 차단한다)</li>
 *   <li>키는 caseId 가 아니라 서버 비밀키다. caseId 는 비밀이 아니어서, 키로 쓰면 누구나 값을 대입해 토큰을 되짚을 수 있다.</li>
 * </ul>
 *
 * 비밀키는 yeogwasigan.tokenize-secret (환경변수 YG_TOKENIZE_SECRET). 비워 두면 실행마다 무작위 키를 만든다.
 * 접두어는 용도에 따라 다르다: TX_(식별자 토큰), PS_(자산 가명), EMAIL_/ACCT_/IP_ ...(본문 내부 재검사).
 */
@Component
public class CaseScopedTokenizer {

    public static final String DEFAULT_PREFIX = "TX_";

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    // ponytail: 비밀키 미설정 시 실행마다 무작위 키라서 재시작하면 같은 사건도 토큰이 바뀐다. 운영은 KMS 키를 주입할 것.
    public CaseScopedTokenizer(@Value("${yeogwasigan.tokenize-secret:}") String secret) {
        byte[] bytes = secret == null || secret.isEmpty() ? randomKey() : secret.getBytes(StandardCharsets.UTF_8);
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    /** 기본 접두어(TX_)로 토큰화한다. */
    public String tokenize(Object value, String caseId) {
        return tokenize(value, caseId, DEFAULT_PREFIX);
    }

    public String tokenize(Object value, String caseId, String prefix) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update((caseId == null ? "" : caseId).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            byte[] digest = mac.doFinal(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(digest).substring(0, 8).toUpperCase(Locale.ROOT);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 을 사용할 수 없습니다.", e);
        }
    }

    private static byte[] randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
