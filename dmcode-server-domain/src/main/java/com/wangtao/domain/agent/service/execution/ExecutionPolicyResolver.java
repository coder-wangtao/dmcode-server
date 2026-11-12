package com.wangtao.domain.agent.service.execution;

import com.wangtao.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.wangtao.domain.agent.model.valobj.execution.ExecutionPolicyVO;
import com.wangtao.domain.agent.model.valobj.intent.IntentResultVO;
import com.wangtao.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import org.springframework.stereotype.Service;

@Service
public class ExecutionPolicyResolver {

    private static final int DEFAULT_MAX_STEPS = 50;
    private static final int DEFAULT_MAX_TOOL_CALLS = 200;
    private static final int DEFAULT_MAX_TOOL_CALLS_PER_ROUND = 10;
    private static final int DEFAULT_MAX_AI_RETRIES = 2;
    private static final long DEFAULT_TOOL_TIMEOUT_MS = 60_000L;
    private static final int DEFAULT_CONTEXT_TOKEN_BUDGET = 8000;

    /**
     * 执行类动词：出现这些词的消息即使被意图分类为 CHAT/CONTINUE/UNKNOWN，
     * 也应视为「要求实际执行」的任务，不允许进入只读策略。
     * 例如「完善这些内容」「开始处理」「帮我修复」「执行吧」。
     */
    private static final java.util.regex.Pattern EXECUTION_VERB_PATTERN = java.util.regex.Pattern.compile(
            "处理|完善|修复|修改|实现|执行|编写|重构|补充|优化|开始干|动手|搞定|落实|落地|干起来|继续干|继续处理|继续完善|继续修复"
    );

    public ExecutionPolicyVO resolve(IntentResultVO intentResult,
                                     String message,
                                     AiAgentConfigTableVO.Module.ReactBudget budget) {
        // 执行动词优先：显式要求「处理/完善/修复」的消息绝不走只读策略，
        // 即使意图分类把短句误判为 CHAT/CONTINUE。
        // 配置了自定义预算时直接用预算（预算本身是非只读的）；否则按 EXECUTE 意图给中等执行预算。
        if (hasExecutionVerb(message)) {
            if (budget != null) {
                return fromBudget(budget);
            }
            return fromIntent(asExecuteIntent(intentResult), message);
        }
        if (isReadOnlyIntent(intentResult)) {
            // perRound 不能是 1：模型分析项目时通常并行调用 list+read+grep，
            // perRound=1 会导致其余调用被 [PER-ROUND-OVERFLOW] 静默跳过，
            // 用户看到的现象就是「工具持续无返回」。
            return new ExecutionPolicyVO(12, 20, 4, 2, 30_000L, 8_000, true);
        }
        if (budget != null) {
            return fromBudget(budget);
        }
        return fromIntent(intentResult, message);
    }

    private boolean hasExecutionVerb(String message) {
        return message != null && EXECUTION_VERB_PATTERN.matcher(message).find();
    }

    private IntentResultVO asExecuteIntent(IntentResultVO intentResult) {
        // 把执行动词命中的消息统一按 EXECUTE 意图处理，获得中等执行预算
        return IntentResultVO.builder()
                .intent(IntentTypeEnumVO.EXECUTE)
                .confidence(intentResult != null ? intentResult.getConfidence() : 0.5)
                .entities(intentResult != null && intentResult.getEntities() != null
                        ? intentResult.getEntities() : java.util.Map.of())
                .build();
    }

    private boolean isReadOnlyIntent(IntentResultVO intentResult) {
        if (intentResult == null || intentResult.getIntent() == null) {
            return false;
        }
        // 注意：CONTINUE（继续上一个任务）不在此列——续作类请求需要实际执行能力，
        // 归为只读会导致「开始处理」「继续完善」等指令被拦截写工具。
        return switch (intentResult.getIntent()) {
            case CHAT, EXPLAIN, SEARCH -> true;
            default -> false;
        };
    }

    private ExecutionPolicyVO fromBudget(AiAgentConfigTableVO.Module.ReactBudget budget) {
        return new ExecutionPolicyVO(
                budget.getMaxSteps() != null ? budget.getMaxSteps() : DEFAULT_MAX_STEPS,
                budget.getMaxToolCalls() != null ? budget.getMaxToolCalls() : DEFAULT_MAX_TOOL_CALLS,
                budget.getMaxToolCallsPerRound() != null ? budget.getMaxToolCallsPerRound() : DEFAULT_MAX_TOOL_CALLS_PER_ROUND,
                budget.getMaxAiRetries() != null ? budget.getMaxAiRetries() : DEFAULT_MAX_AI_RETRIES,
                budget.getToolTimeoutMs() != null ? budget.getToolTimeoutMs() : DEFAULT_TOOL_TIMEOUT_MS,
                budget.getContextTokenBudget() != null && budget.getContextTokenBudget() > 0
                        ? budget.getContextTokenBudget()
                        : DEFAULT_CONTEXT_TOKEN_BUDGET,
                false
        );
    }

    private ExecutionPolicyVO fromIntent(IntentResultVO intentResult, String message) {
        IntentTypeEnumVO intent = intentResult != null && intentResult.getIntent() != null
                ? intentResult.getIntent()
                : IntentTypeEnumVO.UNKNOWN;

        return switch (intent) {
            case DIAGNOSE, CONFIGURE -> new ExecutionPolicyVO(70, 240, 30, 3, 90_000L, 12000, false);
            case DEPLOY, SECURITY, BACKUP -> new ExecutionPolicyVO(80, 280, 30, 3, 120_000L, 16000, false);
            case EXECUTE, MONITOR -> new ExecutionPolicyVO(40, 160, 24, 2, 60_000L, 10000, false);
            case SEARCH, EXPLAIN -> new ExecutionPolicyVO(30, 120, 24, 2, 45_000L, 10000, false);
            case CHAT, CONTINUE -> new ExecutionPolicyVO(20, 50, 16, 1, 25_000L, 8000, false);
            case UNKNOWN -> messageLength(message) > 200
                    ? new ExecutionPolicyVO(60, 220, 24, 2, DEFAULT_TOOL_TIMEOUT_MS, 10000, false)
                    : new ExecutionPolicyVO(DEFAULT_MAX_STEPS, DEFAULT_MAX_TOOL_CALLS, DEFAULT_MAX_TOOL_CALLS_PER_ROUND, DEFAULT_MAX_AI_RETRIES, DEFAULT_TOOL_TIMEOUT_MS, DEFAULT_CONTEXT_TOKEN_BUDGET, false);
        };
    }

    private int messageLength(String message) {
        return message == null ? 0 : message.length();
    }
}
