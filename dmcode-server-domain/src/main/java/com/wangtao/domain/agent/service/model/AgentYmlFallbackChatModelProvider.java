package com.wangtao.domain.agent.service.model;

import com.wangtao.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.wangtao.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
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

import java.time.Duration;

/**
 * 智能体 yml 兜底 ChatModel 提供者
 *
 * <p>从 {@link AiAgentAutoConfigProperties}（即 application-*.yml 通过 spring.config.import
 * 导入的智能体配置，如 agent/code-agent.yml 的 module.ai-api + module.chat-model）中
 * 懒构建默认 ChatModel，供意图识别、任务拆解等"弱模型"场景在用户未在数据库配置任何模型时兜底。
 *
 * <p>模型解析优先级（由调用方保证）：
 * 1. 数据库模型配置（UserModelChatModelFactory，含启动时从智能体 yml 播种的"默认模型"）
 * 2. 智能体 yml 的 ai-api / chat-model 配置（本类）
 *
 * @author walissh dev
 * 2026/10/7
 */
@Slf4j
@Service
public class AgentYmlFallbackChatModelProvider {

    private static final int READ_TIMEOUT_MS = 60_000;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    /** 懒构建并缓存的兜底 ChatModel；构建失败时保持 null */
    private volatile ChatModel fallbackChatModel;

    /**
     * 获取智能体 yml 配置对应的兜底 ChatModel。
     *
     * @return 兜底 ChatModel；yml 中缺少 ai-api/chat-model 配置或配置不完整时返回 null
     */
    public ChatModel getFallbackChatModel() {
        ChatModel model = fallbackChatModel;
        if (model != null) {
            return model;
        }
        synchronized (this) {
            if (fallbackChatModel != null) {
                return fallbackChatModel;
            }
            fallbackChatModel = buildFromAgentYml();
            return fallbackChatModel;
        }
    }

    private ChatModel buildFromAgentYml() {
        AiAgentConfigTableVO table = aiAgentAutoConfigProperties.getTables() == null ? null
                : aiAgentAutoConfigProperties.getTables().values().stream().findFirst().orElse(null);
        if (table == null || table.getModule() == null
                || table.getModule().getAiApi() == null || table.getModule().getChatModel() == null) {
            log.warn("智能体 yml 中缺少 module.ai-api / module.chat-model 配置，无法构建兜底模型");
            return null;
        }

        String baseUrl = table.getModule().getAiApi().getBaseUrl();
        String apiKey = table.getModule().getAiApi().getApiKey();
        String modelName = table.getModule().getChatModel().getModel();
        if (StringUtils.isBlank(baseUrl) || StringUtils.isBlank(apiKey) || StringUtils.isBlank(modelName)) {
            log.warn("智能体 yml ai-api 配置不完整（base-url/api-key/model 存在空值），无法构建兜底模型");
            return null;
        }

        OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .build();
        OpenAIClientAsync openAiClientAsync = OpenAIOkHttpClientAsync.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClientAsync)
                .options(OpenAiChatOptions.builder().model(modelName).build())
                .build();

        log.info("兜底 ChatModel 构建完成（来源：智能体 yml 配置）: model={}, baseUrl={}", modelName, baseUrl);
        return chatModel;
    }
}
