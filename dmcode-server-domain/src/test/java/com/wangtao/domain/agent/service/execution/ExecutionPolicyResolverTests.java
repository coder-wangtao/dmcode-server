package com.wangtao.domain.agent.service.execution;

import com.wangtao.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.wangtao.domain.agent.model.valobj.execution.ExecutionPolicyVO;
import com.wangtao.domain.agent.model.valobj.intent.IntentResultVO;
import com.wangtao.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionPolicyResolverTests {

    private final ExecutionPolicyResolver resolver = new ExecutionPolicyResolver();

    @Test
    void resolveUsesAgentBudgetWhenPresent() {
        AiAgentConfigTableVO.Module.ReactBudget budget = new AiAgentConfigTableVO.Module.ReactBudget();
        budget.setMaxSteps(10);
        budget.setMaxToolCalls(20);
        budget.setMaxToolCallsPerRound(3);
        budget.setMaxAiRetries(1);
        budget.setToolTimeoutMs(5000L);
        budget.setContextTokenBudget(1000);

        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.UNKNOWN).build(),
                "any",
                budget
        );

        assertEquals(10, policy.maxSteps());
        assertEquals(20, policy.maxToolCalls());
        assertEquals(3, policy.maxToolCallsPerRound());
        assertEquals(1, policy.maxAiRetries());
        assertEquals(5000L, policy.toolTimeoutMs());
        assertEquals(1000, policy.contextTokenBudget());
        assertEquals(false, policy.readOnly());
    }

    @Test
    void resolveUnknownShortMessageUsesDefaultPolicy() {
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.UNKNOWN).build(),
                "short",
                null
        );

        assertEquals(50, policy.maxSteps());
        assertEquals(200, policy.maxToolCalls());
        assertEquals(10, policy.maxToolCallsPerRound());
        assertEquals(2, policy.maxAiRetries());
        assertEquals(60_000L, policy.toolTimeoutMs());
        assertEquals(8000, policy.contextTokenBudget());
        assertEquals(false, policy.readOnly());
    }

    @Test
    void resolveProjectUnderstandingUsesTightReadOnlyPolicy() {
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.EXPLAIN).confidence(0.95).build(),
                "这是什么项目",
                null
        );

        // 只读策略也要保证分析类任务可正常并行读文件（perRound 至少 4）
        assertEquals(12, policy.maxSteps());
        assertEquals(20, policy.maxToolCalls());
        assertEquals(4, policy.maxToolCallsPerRound());
        assertEquals(2, policy.maxAiRetries());
        assertEquals(30_000L, policy.toolTimeoutMs());
        assertEquals(8_000, policy.contextTokenBudget());
        assertEquals(true, policy.readOnly());
    }

    @Test
    void executionVerbEscalatesMisclassifiedShortMessageToExecute() {
        // 「开始处理」这类短句即使被意图分类误判为 CHAT，也必须升级为可执行策略
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.CHAT).confidence(0.6).build(),
                "开始处理",
                null
        );

        assertEquals(false, policy.readOnly());
        assertEquals(40, policy.maxSteps());
    }

    @Test
    void executionVerbEscalatesContinueIntent() {
        // 「完善这些内容」被误判为 CONTINUE 时也必须可执行
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.CONTINUE).confidence(0.7).build(),
                "完善这些内容。",
                null
        );

        assertEquals(false, policy.readOnly());
        assertEquals(40, policy.maxSteps());
    }

    @Test
    void continueIntentIsNoLongerReadOnly() {
        // CONTINUE（继续上一个任务）本质是续作执行，不应进入只读策略
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.CONTINUE).confidence(0.9).build(),
                "继续",
                null
        );

        assertEquals(false, policy.readOnly());
        assertEquals(20, policy.maxSteps());
    }

    @Test
    void pureChatWithoutExecutionVerbStaysReadOnly() {
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.CHAT).confidence(0.9).build(),
                "你好呀，今天天气不错",
                null
        );

        assertEquals(true, policy.readOnly());
    }

    @Test
    void explainWithExecutionVerbEscalates() {
        // 用户明确要求「修复/修改」时，即使分类成 EXPLAIN 也要可执行
        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.EXPLAIN).confidence(0.8).build(),
                "帮我修复一下这个报错",
                null
        );

        assertEquals(false, policy.readOnly());
    }

    @Test
    void executionVerbStillRespectsConfiguredBudget() {
        // 执行动词命中但配置了自定义预算时，用配置预算（只读仍为 false）
        AiAgentConfigTableVO.Module.ReactBudget budget = new AiAgentConfigTableVO.Module.ReactBudget();
        budget.setMaxSteps(10);
        budget.setMaxToolCalls(20);
        budget.setMaxToolCallsPerRound(3);
        budget.setMaxAiRetries(1);
        budget.setToolTimeoutMs(5000L);
        budget.setContextTokenBudget(1000);

        ExecutionPolicyVO policy = resolver.resolve(
                IntentResultVO.builder().intent(IntentTypeEnumVO.CHAT).confidence(0.6).build(),
                "开始处理",
                budget
        );

        assertEquals(false, policy.readOnly());
        assertEquals(10, policy.maxSteps());
    }
}
