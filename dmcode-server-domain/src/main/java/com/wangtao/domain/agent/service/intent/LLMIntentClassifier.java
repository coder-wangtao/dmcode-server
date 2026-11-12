package com.wangtao.domain.agent.service.intent;

import com.wangtao.domain.agent.model.valobj.intent.ConversationContextVO;
import com.wangtao.domain.agent.model.valobj.intent.IntentResultVO;
import com.wangtao.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.wangtao.domain.agent.service.model.AgentYmlFallbackChatModelProvider;
import com.wangtao.domain.agent.service.model.UserModelChatModelFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class LLMIntentClassifier implements IIntentClassifier {

    /**
     * 兜底模型提供者：用户未在数据库配置任何模型时，
     * 使用智能体 yml（如 code-agent.yml 的 ai-api / chat-model）构建的默认模型。
     */
    @Resource
    private AgentYmlFallbackChatModelProvider agentYmlFallbackChatModelProvider;

    @Resource
    private UserModelChatModelFactory userModelChatModelFactory;

    private static final String CLASSIFY_PROMPT_TEMPLATE = """
        你是一个编码运维助手的意图识别系统。分析用户输入，返回 JSON 格式的意图分类结果。

        ## 意图类型
        - DIAGNOSE: 诊断问题（服务挂了、报错、异常排查）
        - CONFIGURE: 配置修改（改配置文件、调参数）
        - DEPLOY: 部署操作（部署、发布、回滚）
        - MONITOR: 监控查看（看日志、查状态、看资源使用）
        - SECURITY: 安全相关（防火墙、权限、证书）
        - BACKUP: 备份恢复（备份数据、恢复数据）
        - EXECUTE: 执行任务（写代码、改代码、修 Bug、完善功能、按要求实际修改文件）
        - EXPLAIN: 解释说明（这个命令什么意思）
        - SEARCH: 搜索查找（找文件、查进程）
        - CHAT: 闲聊
        - CONTINUE: 继续上一个任务
        - UNKNOWN: 无法判断

        ## 输出格式（仅返回 JSON，无其他内容）
        {"intent":"类型","confidence":0.0-1.0,"entities":{"key":"value"}}

        ## 示例
        输入: "nginx 502了，帮我看看"
        输出: {"intent":"DIAGNOSE","confidence":0.95,"entities":{"service":"nginx","error":"502"}}

        输入: "帮我改下 redis 的 maxmemory 配置"
        输出: {"intent":"CONFIGURE","confidence":0.9,"entities":{"service":"redis","config":"maxmemory"}}

        输入: "看下服务器磁盘使用情况"
        输出: {"intent":"MONITOR","confidence":0.9,"entities":{"resource":"disk"}}

        输入: "这个命令 awk '{print $1}' access.log 是什么意思"
        输出: {"intent":"EXPLAIN","confidence":0.95,"entities":{"command":"awk"}}

        ## 分类要点（必须遵守）
        1. 含「处理/完善/修复/修改/实现/执行/编写/重构/优化」等动词的指令，无论多短，都是要求实际动手的任务 → 判 EXECUTE 或对应执行类意图，禁止判 CHAT。
        2. 当前输入包含指代词（这些/那些/上面的/它/按之前）时，必须结合「最近对话内容」判断指代对象，再决定意图。例如上文列出了风险清单，用户说「开始完善」→ EXECUTE（实施上文清单）。
        3. 纯问候、寒暄、与项目无关的闲聊 → CHAT。

        ## 对话上下文
        最近意图: %s
        %s
        ## 分析以下输入
        输入: "%s"
        输出:
        """;

    @Override
    public IntentResultVO classify(String message, ConversationContextVO context) {
        return classify(message, context, null);
    }

    @Override
    public IntentResultVO classify(String message, ConversationContextVO context, Long modelId) {
        return classify(message, context, modelId, null);
    }

    @Override
    public IntentResultVO classify(String message, ConversationContextVO context, Long modelId,
                                   java.util.List<String> recentUserMessages) {
        String recentIntents = "";
        if (context != null && context.getRecentIntents() != null) {
            recentIntents = context.getRecentIntents().stream()
                .map(h -> h.getIntent().name())
                .collect(Collectors.joining(", "));
        }

        // 最近对话内容：让 LLM 能解析「开始完善」「按上面的处理」等指代上文的指令
        String recentDialogue = "";
        if (recentUserMessages != null && !recentUserMessages.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int maxChars = 3000; // 控制分类 prompt 大小
            for (String msg : recentUserMessages) {
                if (msg == null || msg.isBlank()) continue;
                String snippet = msg.length() > 400 ? msg.substring(0, 400) + "…(截断)" : msg;
                sb.append("用户: ").append(snippet).append("\n");
                if (sb.length() > maxChars) break;
            }
            if (sb.length() > 0) {
                recentDialogue = "\n## 最近对话内容（用户此前的消息，当前输入可能指代其中内容）\n" + sb;
            }
        }

        String prompt = String.format(CLASSIFY_PROMPT_TEMPLATE,
            recentIntents.isEmpty() ? "无" : recentIntents,
            recentDialogue,
            message);

        ChatModel chatModel = resolveChatModel(modelId);
        if (chatModel == null) {
            log.warn("意图分类无可用模型（用户未配置模型），返回 UNKNOWN");
            return IntentResultVO.builder()
                .intent(IntentTypeEnumVO.UNKNOWN).confidence(0.0).entities(Map.of()).build();
        }

        try {
            // 意图识别需要更确定的结果，按调用覆盖 temperature
            Prompt classifyPrompt = new Prompt(prompt, OpenAiChatOptions.builder().temperature(0.1).build());
            String response = chatModel.call(classifyPrompt).getResult().getOutput().getText();
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("意图分类 LLM 调用失败: {}", e.getMessage());
            return IntentResultVO.builder()
                .intent(IntentTypeEnumVO.UNKNOWN).confidence(0.0).entities(Map.of()).build();
        }
    }

    /**
     * 优先使用数据库中用户配置的模型（ModelConfig，含启动时从智能体 yml 播种的默认模型）；
     * 数据库无任何模型配置时，回退到智能体 yml 的 ai-api / chat-model 配置。
     */
    private ChatModel resolveChatModel(Long modelId) {
        ChatModel userChatModel = userModelChatModelFactory.getChatModel(modelId);
        if (userChatModel != null) {
            return userChatModel;
        }
        if (modelId != null) {
            log.info("意图分类回退默认模型: modelId={} 无对应配置", modelId);
        }
        return agentYmlFallbackChatModelProvider.getFallbackChatModel();
    }

    @SuppressWarnings("unchecked")
    private IntentResultVO parseResponse(String response) {
        try {
            // 提取 JSON 部分
            String json = response.replaceAll("(?s).*?(\\{.*}).*", "$1");
            Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);

            IntentTypeEnumVO intent = IntentTypeEnumVO.valueOf(
                String.valueOf(parsed.get("intent")).toUpperCase());
            double confidence = parsed.containsKey("confidence")
                ? Double.parseDouble(String.valueOf(parsed.get("confidence"))) : 0.5;
            Map<String, String> entities = parsed.containsKey("entities")
                ? (Map<String, String>) parsed.get("entities") : Map.of();

            return IntentResultVO.builder()
                .intent(intent).confidence(confidence)
                .entities(entities).rawResponse(response).build();
        } catch (Exception e) {
            return IntentResultVO.builder()
                .intent(IntentTypeEnumVO.UNKNOWN).confidence(0.0)
                .entities(Map.of()).rawResponse(response).build();
        }
    }
}
