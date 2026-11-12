package com.wangtao.domain.agent.service.prompt.dynamic;

import com.wangtao.domain.agent.model.valobj.prompt.MilestoneVO;
import com.wangtao.domain.agent.model.valobj.prompt.PromptContextVO;
import com.wangtao.domain.agent.model.valobj.prompt.TaskModeVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DynamicPromptBuilder {

    public String build(String baseInstruction, PromptContextVO ctx) {
        if (ctx == null) {
            return baseInstruction;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(baseInstruction);

        appendEnvironmentInfo(sb, ctx);
        appendProjectInfo(sb, ctx);
        appendRecentCommands(sb, ctx);
        appendMilestones(sb, ctx);
        appendCoreMemories(sb, ctx);
        appendToolResultSummary(sb, ctx);
        appendTaskDescription(sb, ctx);

        String result = sb.toString();
        log.debug("动态 Prompt 构建完成，长度: {} (基础: {}, 动态: {})",
                result.length(), baseInstruction.length(), result.length() - baseInstruction.length());
        return result;
    }

    /**
     * 将动态上下文构建为用户消息前缀（注入到用户消息中）
     * 适用于无法直接修改 system instruction 的场景
     * <p>
     * [FIX-20260626] 上下文残留修复：
     * - 系统环境、SSH连接、当前工程 → 这些是实时状态，直接注入
     * - 关键事件、最近命令、工具摘要、核心记忆 → 这些来自历史对话，标注"历史上下文"
     *   并添加提示"请以当前用户消息的意图为主"，避免历史任务误导新意图
     */
    public String buildMessagePrefix(PromptContextVO ctx) {
        if (ctx == null) return "";

        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        if (ctx.getTaskMode() != null) {
            appendTaskMode(sb, ctx.getTaskMode());
            hasContent = true;
        }

        // ── 实时状态区（不受历史对话影响） ──

        if (!isEmpty(ctx.getServerInfo()) || !isEmpty(ctx.getOsInfo())
                || !isEmpty(ctx.getCurrentUser()) || !isEmpty(ctx.getCurrentDirectory())) {
            sb.append("[系统环境]\n");
            if (!isEmpty(ctx.getServerInfo()))       sb.append("服务器: ").append(ctx.getServerInfo()).append("\n");
            if (!isEmpty(ctx.getOsInfo()))           sb.append("系统: ").append(ctx.getOsInfo()).append("\n");
            if (!isEmpty(ctx.getCurrentUser()))      sb.append("用户: ").append(ctx.getCurrentUser()).append("\n");
            if (!isEmpty(ctx.getCurrentDirectory())) sb.append("目录: ").append(ctx.getCurrentDirectory()).append("\n");
            hasContent = true;
        }

        // SSH 连接信息
        if (!isEmpty(ctx.getSshConnectionName()) || !isEmpty(ctx.getSshHost()) || !isEmpty(ctx.getSshUsername())) {
            sb.append("\n[SSH连接]\n");
            if (!isEmpty(ctx.getSshConnectionName())) sb.append("连接名: ").append(ctx.getSshConnectionName()).append("\n");
            if (!isEmpty(ctx.getSshHost())) {
                sb.append("主机: ").append(ctx.getSshHost());
                if (ctx.getSshPort() != null) sb.append(":").append(ctx.getSshPort());
                sb.append("\n");
            }
            if (!isEmpty(ctx.getSshUsername())) sb.append("用户: ").append(ctx.getSshUsername()).append("\n");
            sb.append("已连接SSH，执行命令请使用 executeCommand\n");
            hasContent = true;
        }

        if (!isEmpty(ctx.getProjectName()) || !isEmpty(ctx.getProjectRootPath())) {
            sb.append("\n[当前工程]\n");
            if (!isEmpty(ctx.getProjectName()))     sb.append("工程名: ").append(ctx.getProjectName()).append("\n");
            if (!isEmpty(ctx.getProjectRootPath())) sb.append("工程路径: ").append(ctx.getProjectRootPath()).append("\n");
            hasContent = true;
        }

        // ── 任务上下文区（当前指令的指代对象，模型必须结合它理解「这些内容」等指代词） ──
        // 注意：这块不属于「可忽略的历史」，而是当前执行指令的直接上下文。
        if (!isEmpty(ctx.getTaskDescription())) {
            sb.append("\n[当前任务]\n").append(ctx.getTaskDescription()).append("\n");
            hasContent = true;
        }

        if (!isEmpty(ctx.getPriorUserInstructions())) {
            sb.append("\n[本会话此前的用户指令 — 当用户说「这些内容」「按上面的」「完善它们」时，指代对象就在这里，必须结合它执行]\n");
            sb.append(ctx.getPriorUserInstructions()).append("\n");
            hasContent = true;
        }

        // ── 历史上下文区（来自之前对话的命令/事件记录，标注以避免误导） ──
        boolean hasHistoricalContext = false;

        if (ctx.getRecentCommands() != null && !ctx.getRecentCommands().isEmpty()) {
            if (!hasHistoricalContext) {
                sb.append("\n[历史对话记录 — 以下信息来自之前对话，请以当前用户消息的意图为主]\n");
                hasHistoricalContext = true;
            }
            sb.append("最近执行的命令:\n");
            for (String cmd : ctx.getRecentCommands()) {
                sb.append("- ").append(cmd).append("\n");
            }
            hasContent = true;
        }

        if (ctx.getMilestoneVOS() != null && !ctx.getMilestoneVOS().isEmpty()) {
            if (!hasHistoricalContext) {
                sb.append("\n[历史对话记录 — 以下信息来自之前对话，请以当前用户消息的意图为主]\n");
                hasHistoricalContext = true;
            }
            sb.append("关键事件:\n");
            for (MilestoneVO m : ctx.getMilestoneVOS()) {
                sb.append("- [").append(m.getType().name()).append("] ").append(m.getContent()).append("\n");
            }
            hasContent = true;
        }

        if (!isEmpty(ctx.getToolResultSummary())) {
            if (!hasHistoricalContext) {
                sb.append("\n[历史对话记录 — 以下信息来自之前对话，请以当前用户消息的意图为主]\n");
                hasHistoricalContext = true;
            }
            sb.append("工具执行摘要:\n").append(ctx.getToolResultSummary()).append("\n");
            hasContent = true;
        }

        // ── 长期记忆区（跨会话持久记忆） ──

        if (!isEmpty(ctx.getCoreMemories())) {
            sb.append("\n[核心记忆 — 跨会话持久偏好]\n").append(ctx.getCoreMemories()).append("\n");
            hasContent = true;
        }

        if (!hasContent) return "";

        String prefix = sb.toString();
        log.debug("构建消息前缀，长度: {}", prefix.length());
        return prefix;
    }

    private void appendTaskMode(StringBuilder sb, TaskModeVO taskMode) {
        sb.append("[本轮任务模式]\n");
        switch (taskMode) {
            case EXECUTE -> sb.append("当前输入是明确执行或续作指令。结合既有分析立即执行；只有不可逆操作或缺少必要安全参数时才确认。\n");
            case STATUS -> sb.append("当前输入是状态确认或缺口查询，不是修改指令。只做读取和检查，禁止写入、创建、删除或执行有副作用的命令；基于证据回答完成状态和欠缺项。\n");
            case CLARIFY -> sb.append("当前输入缺少目标或必要参数。先复述已理解的目标，再提出最少必要问题；禁止修改文件。\n");
            case CONVERSATIONAL -> sb.append("按当前问题回答；只有用户明确要求变更时才允许修改文件。\n");
        }
    }

    public String buildOutputDiscipline() {
        return """
                [Agent 输出纪律]
                1. 每次调用工具前，必须先用一句不超过 30 个字的简体中文说明本次动作和目的。
                2. 同一批并行工具调用只需要一句说明；后续工具如果没有新的决策，不要再重复说明。
                3. 不要重复之前的计划、工具结果或结论。
                4. 如果已有足够信息，立即给出最终回复，不要发起重复或无收益的工具调用。
                5. 最终回复只保留一份完整结论；不得复述过程句、开头内容或工具返回。
                6. 所有面向用户的内容必须使用简体中文；禁止输出英文句子或英文段落。
                7. 代码、路径、命令、日志、依赖名和专有名词保持原文；代码注释优先使用中文。
                8. 如果内部模型倾向输出英文，必须在展示前转为简体中文。
                """;
    }

    private void appendEnvironmentInfo(StringBuilder sb, PromptContextVO ctx) {
        boolean hasAnyEnv = !isEmpty(ctx.getServerInfo()) || !isEmpty(ctx.getOsInfo())
                || !isEmpty(ctx.getCurrentUser()) || !isEmpty(ctx.getCurrentDirectory());
        boolean hasSshConnection = !isEmpty(ctx.getSshConnectionName()) || !isEmpty(ctx.getSshHost())
                || !isEmpty(ctx.getSshUsername());
        if (!hasAnyEnv && !hasSshConnection) {
            return;
        }
        sb.append("\n\n## 当前环境信息\n");
        if (!isEmpty(ctx.getServerInfo()))       sb.append("- 服务器: ").append(ctx.getServerInfo()).append("\n");
        if (!isEmpty(ctx.getOsInfo()))           sb.append("- 操作系统: ").append(ctx.getOsInfo()).append("\n");
        if (!isEmpty(ctx.getCurrentUser()))      sb.append("- 当前用户: ").append(ctx.getCurrentUser()).append("\n");
        if (!isEmpty(ctx.getCurrentDirectory())) sb.append("- 工作目录: ").append(ctx.getCurrentDirectory()).append("\n");
        // SSH 连接信息
        if (hasSshConnection) {
            sb.append("\n### SSH 连接\n");
            if (!isEmpty(ctx.getSshConnectionName())) sb.append("- 连接名: ").append(ctx.getSshConnectionName()).append("\n");
            if (!isEmpty(ctx.getSshHost()))           sb.append("- 主机: ").append(ctx.getSshHost());
            if (ctx.getSshPort() != null)             sb.append(":").append(ctx.getSshPort());
            sb.append("\n");
            if (!isEmpty(ctx.getSshUsername()))       sb.append("- 用户: ").append(ctx.getSshUsername()).append("\n");
            sb.append("- **你已连接到此 SSH 服务器，执行命令时应使用 executeCommand 工具**\n");
        }
    }

    private void appendProjectInfo(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getProjectName()) && isEmpty(ctx.getProjectRootPath())) {
            return;
        }
        sb.append("\n\n## 当前工程\n");
        if (!isEmpty(ctx.getProjectName()))     sb.append("- 工程名: ").append(ctx.getProjectName()).append("\n");
        if (!isEmpty(ctx.getProjectRootPath())) sb.append("- 工程路径: ").append(ctx.getProjectRootPath()).append("\n");
    }

    private void appendRecentCommands(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getRecentCommands() == null || ctx.getRecentCommands().isEmpty()) return;
        sb.append("\n## 最近操作记录（历史对话，请以当前用户意图为主）\n");
        for (String cmd : ctx.getRecentCommands()) {
            sb.append("- ").append(cmd).append("\n");
        }
    }

    private void appendMilestones(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getMilestoneVOS() == null || ctx.getMilestoneVOS().isEmpty()) return;
        sb.append("\n## 关键事件（历史对话，请以当前用户意图为主）\n");
        for (MilestoneVO m : ctx.getMilestoneVOS()) {
            sb.append("- [").append(m.getType().name()).append("] ").append(m.getContent()).append("\n");
        }
    }

    private void appendCoreMemories(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getCoreMemories())) return;
        sb.append("\n\n## 核心记忆\n");
        sb.append(ctx.getCoreMemories()).append("\n");
    }

    private void appendToolResultSummary(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getToolResultSummary())) return;
        sb.append("\n\n## 工具执行摘要\n");
        sb.append(ctx.getToolResultSummary()).append("\n");
    }

    private void appendTaskDescription(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getTaskDescription())) return;
        sb.append("\n\n## 当前任务\n");
        sb.append(ctx.getTaskDescription()).append("\n");
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
