package com.wangtao.cases.react.node;

import com.wangtao.api.dto.ChatRequestDTO;
import com.wangtao.api.dto.ReActResultDTO;
import com.wangtao.cases.react.AbstractAIAgentReActSupport;
import com.wangtao.cases.react.factory.DefaultReActFactory;
import com.wangtao.domain.agent.adapter.repository.IChatHistoryRepository;
import com.wangtao.domain.agent.model.entity.ChatMessageEntity;
import com.wangtao.domain.agent.model.entity.ChatSessionEntity;
import com.wangtao.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.wangtao.domain.agent.model.valobj.prompt.TaskModeVO;
import com.wangtao.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.wangtao.domain.agent.service.execution.ExecutionPolicyResolver;
import com.wangtao.domain.agent.service.prompt.TaskModeClassifier;
import com.wangtao.domain.agent.model.valobj.execution.ExecutionPolicyVO;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ReAct Root Node（根节点）
 *
 * <p>职责：
 * 1. 从 ChatRequestDTO 提取会话参数
 * 2. 初始化 DynamicContext
 * 3. 绑定终端会话 ID（ThreadLocal）
 * 4. 路由到 AiCallNode
 *
 * <p>节点链：
 * RootNode → AiCallNode → ToolCallNode → ToolResultNode → LoopDecisionNode
 *                                                   ↑__________________|
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/5/4 13:57
 */
@Slf4j
@Component("reactRootNode")
public class RootNode extends AbstractAIAgentReActSupport {

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private com.wangtao.domain.agent.service.model.IDynamicAgentRegistry dynamicAgentRegistry;

    @Resource
    private com.wangtao.domain.agent.adapter.repository.IModelConfigRepository modelConfigRepository;

    @Resource
    private ExecutionPolicyResolver executionPolicyResolver;

    @Resource
    private TaskModeClassifier taskModeClassifier;

    private static final int DEFAULT_MAX_STEPS = 50;
    private static final int DEFAULT_MAX_TOOL_CALLS = 200;
    private static final int DEFAULT_MAX_TOOL_CALLS_PER_ROUND = 10;
    private static final int DEFAULT_MAX_AI_RETRIES = 2;
    private static final long DEFAULT_TOOL_TIMEOUT_MS = 60_000L;
    private static final int DEFAULT_CONTEXT_TOKEN_BUDGET = 8000;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct RootNode - 初始化上下文");

        // 1. 提取会话参数
        String sessionId = requestParameter.getSessionId();
        String userId = requestParameter.getUserId();
        String agentId = requestParameter.getAgentId();
        String terminalSessionId = requestParameter.getTerminalSessionId();
        String message = requestParameter.getMessage();

        // 模型解析：请求指定 modelId 优先；未指定时优先用数据库第一个模型配置（与意图分类/任务拆解的
        // UserModelChatModelFactory 回退语义一致），数据库无任何配置才走智能体 yml 内置 ai-api。
        Long effectiveModelId = requestParameter.getModelId();
        if (effectiveModelId == null) {
            effectiveModelId = modelConfigRepository.queryAll().stream().findFirst()
                    .map(com.wangtao.domain.agent.model.entity.ModelConfigEntity::getId)
                    .orElse(null);
        }
        String runtimeAgentId = agentId;
        if (effectiveModelId != null) {
            runtimeAgentId = dynamicAgentRegistry.registerAgentForModel(agentId, effectiveModelId);
        }

        // 2. 绑定终端会话（ThreadLocal + sessionMapping 双通道）
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            setCurrentTerminalSession(terminalSessionId);
            bindTerminalSession(sessionId, terminalSessionId);
            // 同步到 SSH 工具可见性注册中心（绑定后 beforeModelCallback 恢复远程工具可见）
            com.wangtao.domain.agent.service.armory.matter.tools.SshTerminalSessionRegistry.bind(sessionId, terminalSessionId);
        } else {
            // 尝试从会话绑定中获取
            String boundTerminal = getTerminalSession(sessionId);
            if (boundTerminal != null) {
                setCurrentTerminalSession(boundTerminal);
                com.wangtao.domain.agent.service.armory.matter.tools.SshTerminalSessionRegistry.bind(sessionId, boundTerminal);
            }
        }

        // [Phase 5] 自动创建/更新会话元数据
        ChatSessionEntity existingSession = chatHistoryRepository.getSession(sessionId);
        if (existingSession == null) {
            chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                    .id(sessionId)
                    .agentId(agentId)
                    .userId(userId)
                    .title(message != null && message.length() > 50 ? message.substring(0, 50) : message)
                    .messageCount(0)
                    .build());
            log.info("[Phase 5] 自动创建会话记录: sessionId={}, agentId={}", sessionId, agentId);
        }

        // 3. 初始化上下文
        dynamicContext.setSessionId(sessionId);
        dynamicContext.setUserId(userId);
        dynamicContext.setAgentId(runtimeAgentId);
        dynamicContext.setSourceAgentId(agentId);
        dynamicContext.setModelId(requestParameter.getModelId());
        dynamicContext.setTerminalSessionId(terminalSessionId);
        dynamicContext.setProjectContext(requestParameter.getProjectContext());
        dynamicContext.setCurrentToolCalls(new java.util.ArrayList<>());
        dynamicContext.setCurrentToolResults(new java.util.ArrayList<>());

        // [Phase 5] 从数据库加载历史消息
        java.util.List<java.util.Map<String, Object>> history = new java.util.ArrayList<>();
        java.util.List<ChatMessageEntity> recentMessages = chatHistoryRepository.getRecentMessages(sessionId, 50);
        for (ChatMessageEntity msg : recentMessages) {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("role", msg.getRole());
            map.put("content", msg.getContent() != null ? msg.getContent() : "");
            if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                map.put("tool_call_id", msg.getToolCallId());
                map.put("name", msg.getToolName());
            }
            history.add(map);
        }
        dynamicContext.setMessageHistory(history);
        dynamicContext.setCurrentStep(new java.util.concurrent.atomic.AtomicInteger(0));

        // [2026-09-28 架构简化] 移除 LLM 意图识别路由：
        // 实测表明意图分类（无论规则还是 LLM）对执行路由弊大于利——
        // 误判短句导致只读拦截、增加一次 LLM 调用延迟、分类结果模型并不需要。
        // 现在统一使用 agent YAML 配置的完整执行预算，只读与否交给模型依据
        // instruction 里的规则自行判断（真正能理解「开始改进」= 动手改代码的只有模型自己）。
        ExecutionPolicyVO executionPolicy = resolveUnifiedPolicy(agentId);

        String priorUserInstructions = history.stream()
                .filter(item -> "user".equals(item.get("role")) && item.get("content") != null)
                .map(item -> String.valueOf(item.get("content")))
                .collect(java.util.stream.Collectors.joining("\n---\n"));
        TaskModeVO taskMode = taskModeClassifier.classify(message, priorUserInstructions);

        dynamicContext.setCurrentIntent("UNIFIED");
        dynamicContext.setCurrentIntentConfidence(1.0D);
        dynamicContext.setMaxSteps(executionPolicy.maxSteps());
        dynamicContext.setMaxToolCalls(executionPolicy.maxToolCalls());
        dynamicContext.setMaxToolCallsPerRound(executionPolicy.maxToolCallsPerRound());
        dynamicContext.setMaxAiRetries(executionPolicy.maxAiRetries());
        dynamicContext.setToolTimeoutMs(executionPolicy.toolTimeoutMs());
        dynamicContext.setContextTokenBudget(executionPolicy.contextTokenBudget());
        dynamicContext.setTaskMode(taskMode);
        dynamicContext.setReadOnlyExecution(executionPolicy.readOnly() || taskMode == TaskModeVO.STATUS);
        dynamicContext.resetAiRetryCount();

        log.info("ReAct 执行预算: intent=UNIFIED(已移除意图路由), taskMode={}, maxSteps={}, maxToolCalls={}, perRound={}, maxAiRetries={}, toolTimeoutMs={}, contextTokenBudget={}, readOnly={}",
                taskMode,
                executionPolicy.maxSteps(),
                executionPolicy.maxToolCalls(),
                executionPolicy.maxToolCallsPerRound(),
                executionPolicy.maxAiRetries(),
                executionPolicy.toolTimeoutMs(),
                executionPolicy.contextTokenBudget(),
                executionPolicy.readOnly());

        // 4. 初始化结果 DTO
        ReActResultDTO result = ReActResultDTO.builder()
                .totalSteps(0)
                .totalToolCalls(0)
                .maxStepsReached(false)
                .userStopped(false)
                .idleTimeout(false)
                .build();
        dynamicContext.setResult(result);

        // 5. 追加用户消息到历史
        dynamicContext.appendUserMessage(message);

        log.info("ReAct RootNode - 初始化完成 sessionId={}, userId={}, agentId={}, terminalSessionId={}",
                sessionId, userId, agentId, terminalSessionId);

        // 6. 路由到 AI 调用节点
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        // RootNode → TaskBreakdownNode（检测是否需要拆解）→ AiCallNode
        return getBean("reactTaskBreakdownNode");
    }

    /**
     * 从 agent YAML 配置读取 reactBudget
     */
    private AiAgentConfigTableVO.Module.ReactBudget resolveReactBudget(String agentId) {
        if (agentId == null || aiAgentAutoConfigProperties.getTables() == null) {
            return null;
        }
        for (AiAgentConfigTableVO table : aiAgentAutoConfigProperties.getTables().values()) {
            if (table.getAgent() != null && agentId.equals(table.getAgent().getAgentId())) {
                if (table.getModule() != null && table.getModule().getRunner() != null) {
                    return table.getModule().getRunner().getReactBudget();
                }
            }
        }
        return null;
    }

    /**
     * 统一执行预算：优先取 agent YAML 配置的 reactBudget；
     * 未配置时给一套宽裕的默认值（不区分意图、不设只读）。
     */
    private ExecutionPolicyVO resolveUnifiedPolicy(String agentId) {
        AiAgentConfigTableVO.Module.ReactBudget budget = resolveReactBudget(agentId);
        if (budget != null) {
            return new ExecutionPolicyVO(
                    budget.getMaxSteps() != null ? budget.getMaxSteps() : 80,
                    budget.getMaxToolCalls() != null ? budget.getMaxToolCalls() : 300,
                    budget.getMaxToolCallsPerRound() != null ? budget.getMaxToolCallsPerRound() : 200,
                    budget.getMaxAiRetries() != null ? budget.getMaxAiRetries() : 3,
                    budget.getToolTimeoutMs() != null ? budget.getToolTimeoutMs() : 90_000L,
                    budget.getContextTokenBudget() != null && budget.getContextTokenBudget() > 0
                            ? budget.getContextTokenBudget() : 16_000,
                    false
            );
        }
        return new ExecutionPolicyVO(80, 300, 200, 3, 90_000L, 16_000, false);
    }

}
