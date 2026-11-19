package com.wangtao.domain.agent.service.model;

import com.wangtao.domain.agent.adapter.repository.IModelConfigRepository;
import com.wangtao.domain.agent.model.entity.ModelConfigEntity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户模型 ChatModel 工厂
 *
 * <p>意图分类、任务拆解等"弱模型"调用场景复用此工厂：
 * <b>优先使用用户在数据库里配置的模型</b>（ModelConfigEntity，含启动时从智能体 yml 播种的默认模型），
 * 仅当数据库无任何模型配置时才由调用方回退到智能体 yml（AgentYmlFallbackChatModelProvider）的 ai-api 配置。
 *
 * <p>构建的 ChatModel 带有连接/读取超时，且禁用内部重试 —— 避免 LLM 网关不可达时
 * 触发底层 RetryTemplate 长时间重试导致用户侧"长时间无响应"。
 *
 *

 */
@Slf4j
@Service
public class UserModelChatModelFactory {

    private static final String DEFAULT_COMPLETIONS_PATH = "v1/chat/completions";

    /** 意图分类/任务拆解属于辅助调用，超时从严：连接 5s、读取 60s */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    @Resource
    private IModelConfigRepository modelConfigRepository;

    /** modelId -> ChatModel 缓存；模型配置保存/删除时由 ModelConfigController 触发 evict */
    private final Map<Long, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    /**
     * 按请求中的 modelId 获取用户配置的 ChatModel。
     *
     * @param modelId 前端请求携带的模型 ID，可为 null（则取第一个用户模型配置）
     * @return 用户配置模型对应的 ChatModel；用户未配置任何模型时返回 null（由调用方回退）
     */
    public ChatModel getChatModel(Long modelId) {
        ModelConfigEntity config = resolveConfig(modelId);
        if (config == null) {
            return null;
        }
        return chatModelCache.computeIfAbsent(config.getId(), id -> buildChatModel(config));
    }

    /**
     * 用户是否已配置过模型（用于调用方决定是否回退默认网关）。
     */
    public boolean hasUserModel() {
        return !modelConfigRepository.queryAll().isEmpty();
    }

    /**
     * 模型配置变更后清除缓存（保存/删除时调用）。
     */
    public void evict(Long modelConfigId) {
        if (modelConfigId != null) {
            chatModelCache.remove(modelConfigId);
        } else {
            chatModelCache.clear();
        }
    }

    private ModelConfigEntity resolveConfig(Long modelId) {
        if (modelId != null) {
            ModelConfigEntity config = modelConfigRepository.queryById(modelId);
            if (config != null) {
                return config;
            }
            log.warn("指定的模型配置不存在: modelId={}，尝试回退到第一个用户模型", modelId);
        }
        return modelConfigRepository.queryAll().stream().findFirst().orElse(null);
    }

    private ChatModel buildChatModel(ModelConfigEntity config) {
        String completionsPath = StringUtils.defaultIfBlank(config.getCompletionsPath(), DEFAULT_COMPLETIONS_PATH);

        OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .timeout(java.time.Duration.ofMillis(READ_TIMEOUT_MS))
                .build();
        OpenAIClientAsync openAiClientAsync = OpenAIOkHttpClientAsync.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .timeout(java.time.Duration.ofMillis(READ_TIMEOUT_MS))
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClientAsync)
                .options(OpenAiChatOptions.builder()
                        .model(config.getModelName())
                        .build())
                .build();

        log.info("用户模型 ChatModel 构建完成: modelConfigId={}, name={}, model={}, baseUrl={}",
                config.getId(), config.getName(), config.getModelName(), config.getBaseUrl());
        return chatModel;
    }
}
