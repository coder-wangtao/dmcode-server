package com.wangtao.domain.agent.service.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTests {

    @Test
    void emptyAndNullReturnZero() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void pureEnglishEstimatesAt4CharsPerToken() {
        String text = "Hello World, this is a test";
        long tokens = TokenEstimator.estimate(text);
        assertTrue(tokens > 0);
        assertTrue(tokens < text.length(), "English should be less tokens than chars");
    }

    @Test
    void pureChineseEstimatesAt1Point5PerChar() {
        String text = "你好世界测试代码";
        long tokens = TokenEstimator.estimate(text);
        assertEquals(12, tokens, "8 CJK chars × 1.5 = 12 tokens");
    }

    @Test
    void mixedLanguageEstimatesHigherThanCharDivideBy2() {
        String mixed = "使用 Spring Boot 构建应用程序 and deploy to AWS";
        long tokens = TokenEstimator.estimate(mixed);
        long oldEstimate = mixed.length() / 2;
        assertTrue(tokens > oldEstimate,
                "Mixed language should be more accurate than char/2: " + tokens + " > " + oldEstimate);
    }

    @Test
    void messageEstimationAggregates() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "hello"),
                Map.of("role", "assistant", "content", "你好，我可以帮你"),
                Map.of("role", "user", "content", "thanks")
        );
        long tokens = TokenEstimator.estimateMessages(messages);
        assertTrue(tokens > 0);
    }

    @Test
    void apiKeyPatternIsRedacted() {
        String input = "Use API key sk-abcdefghij1234567890abcdefghij12 for auth";
        String redacted = cn.bugstack.ai.domain.agent.service.security.SecretRedactor.redact(input);
        assertTrue(!redacted.contains("sk-abcdefghij1234567890abcdefghij12"));
        assertTrue(redacted.contains("[REDACTED]"));
    }

    @Test
    void passwordPatternIsRedacted() {
        String input = "password = mySecret123456";
        String redacted = cn.bugstack.ai.domain.agent.service.security.SecretRedactor.redact(input);
        assertTrue(!redacted.contains("mySecret123456"));
    }

    @Test
    void normalCodeIsNotRedacted() {
        String input = "public void process(String data) { return data.trim(); }";
        String redacted = cn.bugstack.ai.domain.agent.service.security.SecretRedactor.redact(input);
        assertEquals(input, redacted);
    }

    @Test
    void privateKeyBlockIsRedacted() {
        String input = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAK\n-----END RSA PRIVATE KEY-----";
        String redacted = cn.bugstack.ai.domain.agent.service.security.SecretRedactor.redact(input);
        assertTrue(!redacted.contains("MIIEowIBAAK"));
    }
}
