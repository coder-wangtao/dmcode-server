package com.wangtao.domain.agent.service.security;

import com.wangtao.domain.agent.service.armory.matter.tools.command.RunContextHolder;

import java.util.regex.Pattern;

public final class ToolExecutionPolicyGuard {

    private static final Pattern MUTATING_COMMAND = Pattern.compile(
            "(?:^|[;&|]\\s*)(?:sudo\\s+)?(?:rm|mv|cp|mkdir|touch|tee|truncate|chmod|chown|git\\s+(?:add|commit|push|reset|clean|checkout)|npm\\s+(?:install|i|uninstall)|pnpm\\s+(?:add|remove|install)|yarn\\s+(?:add|remove|install)|mvn\\s+clean)\\b"
                    + "|(?:>>?|<<<?)\\s*[^\\s|&;]",
            Pattern.CASE_INSENSITIVE
    );

    private ToolExecutionPolicyGuard() {
    }

    public static boolean isMutationTool(String toolName) {
        String normalized = toolName == null ? "" : toolName.toLowerCase();
        return normalized.startsWith("write")
                || normalized.startsWith("create")
                || normalized.startsWith("delete")
                || normalized.startsWith("edit")
                || normalized.contains("rollback")
                || normalized.equals("codeedittool")
                || normalized.equals("applyedit");
    }

    public static void requireMutationAllowed(String toolName) {
        if (isReadOnly() && isMutationTool(toolName)) {
            throw new ToolPolicyViolationException(
                    "当前是项目理解类只读请求，已拦截 " + toolName + "。请基于已读取的信息回答。");
        }
    }

    public static void requireCommandAllowed(String command) {
        if (isReadOnly() && MUTATING_COMMAND.matcher(command).find()) {
            throw new ToolPolicyViolationException(
                    "当前是项目理解类只读请求，已拦截有副作用的命令。");
        }
    }

    private static boolean isReadOnly() {
        String sessionId = RunContextHolder.getSessionId();
        if (sessionId == null) {
            return false;
        }
        RunContextHolder.RunContext context = RunContextHolder.getContextBySession(sessionId);
        return context != null && context.isReadOnly();
    }

    public static class ToolPolicyViolationException extends RuntimeException {
        public ToolPolicyViolationException(String message) {
            super(message);
        }
    }
}
