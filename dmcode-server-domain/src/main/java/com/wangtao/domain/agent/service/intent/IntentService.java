package com.wangtao.domain.agent.service.intent;

import com.wangtao.domain.agent.model.valobj.intent.ConversationContextVO;
import com.wangtao.domain.agent.model.valobj.intent.IntentResultVO;
import com.wangtao.domain.agent.service.IIntentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class IntentService implements IIntentService {

    @Resource
    private RuleIntentClassifier ruleClassifier;

    @Resource
    private LLMIntentClassifier llmClassifier;

    @Resource
    private ContextTracker contextTracker;

    // 简单的 LRU 缓存，最大 200 个条目
    private final Map<String, CacheEntry> cache = Collections.synchronizedMap(
        new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > 200;
            }
        });

    private static class CacheEntry {
        IntentResultVO result;
        long expireTime;
        CacheEntry(IntentResultVO result, long expireTime) {
            this.result = result;
            this.expireTime = expireTime;
        }
    }

    @Override
    public IntentResultVO classify(String sessionId, String userId, String message) {
        return classify(sessionId, userId, message, null);
    }

    @Override
    public IntentResultVO classify(String sessionId, String userId, String message, Long modelId) {
        return classify(sessionId, userId, message, modelId, null);
    }

    /**
     * 分类策略：LLM 为主（能看懂自然语言和多轮指代），规则只做兜底。
     *
     * 旧策略（规则先行）的问题：规则分类器对短句「开始完善」命中 MONITOR 关键词
     * （如「开始」不在规则里但消息里任何关键词都可能加分）后拿到 0.8+ 置信度直接短路返回，
     * LLM 根本没机会参与——这正是用户连续几轮诉求都没被正确执行的根源。
     */
    @Override
    public IntentResultVO classify(String sessionId, String userId, String message, Long modelId,
                                   List<String> recentUserMessages) {
        String cacheKey = sessionId + ":" + modelId + ":" + sha256(message);

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expireTime > System.currentTimeMillis()) {
            log.debug("意图分类缓存命中: session={}", sessionId);
            return cached.result;
        }

        ConversationContextVO context = contextTracker.getContext(sessionId);

        // 第1层：LLM 分类（主通道），带上最近对话内容供指代消解
        IntentResultVO llmResult = llmClassifier.classify(message, context, modelId, recentUserMessages);

        IntentResultVO finalResult;
        if (llmResult.getConfidence() >= 0.5) {
            finalResult = llmResult;
        } else {
            // 第2层：规则兜底（LLM 不可用或低置信度时）
            IntentResultVO ruleResult = ruleClassifier.classify(message, context);
            finalResult = ruleResult.getConfidence() > llmResult.getConfidence() ? ruleResult : llmResult;
            if (finalResult == ruleResult) {
                log.info("意图分类 LLM 低置信度({})，采用规则结果: {}",
                        llmResult.getConfidence(), finalResult.getIntent());
            }
        }

        recordAndCache(sessionId, cacheKey, finalResult, context);
        return finalResult;
    }

    private void recordAndCache(String sessionId, String cacheKey, IntentResultVO result,
                                  ConversationContextVO context) {
        contextTracker.updateContext(sessionId, result);

        // [Phase 3] 记录实体到上下文（供指代消解使用）
        if (context != null && result.getEntities() != null) {
            result.getEntities().forEach(context::putEntity);
        }

        // 缓存 5 分钟
        cache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis() + 5 * 60 * 1000));
    }

    /**
     * [Phase 3 修复] SHA-256 替代 hashCode，避免缓存键碰撞
     */
    private String sha256(String message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16); // 前16位足够
        } catch (Exception e) {
            // 兜底：使用 hashCode
            return Integer.toHexString(message.hashCode());
        }
    }
}
