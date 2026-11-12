package com.wangtao.domain.agent.service.armory.matter.tools.command;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RunContextHolder {

    private static final ThreadLocal<String> RUN_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Path> WORKSPACE = new ThreadLocal<>();

    private RunContextHolder() {}

    public static class RunContext {
        private final String runId;
        private final String sessionId;
        private final String userId;
        private final boolean readOnly;
        private final Path workspace;

        public RunContext(String runId, String sessionId, String userId, boolean readOnly, Path workspace) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.userId = userId;
            this.readOnly = readOnly;
            this.workspace = workspace;
        }

        public String getRunId() { return runId; }
        public String getSessionId() { return sessionId; }
        public String getUserId() { return userId; }
        public boolean isReadOnly() { return readOnly; }
        public Path getWorkspace() { return workspace; }
    }

    /** sessionId → RunContext（供 ADK 异步线程按 session 查找） */
    private static final Map<String, RunContext> sessionContextMap = new ConcurrentHashMap<>();

    public static void set(String runId, String sessionId, String userId, boolean readOnly) {
        set(runId, sessionId, userId, readOnly, null);
    }

    public static void set(String runId, String sessionId, String userId, boolean readOnly, Path workspace) {
        RUN_ID.set(runId);
        SESSION_ID.set(sessionId);
        USER_ID.set(userId);
        WORKSPACE.set(workspace);
        if (sessionId != null && !sessionId.isBlank()) {
            sessionContextMap.put(sessionId, new RunContext(runId, sessionId, userId, readOnly, workspace));
        }
    }

    public static String getRunId() {
        String value = RUN_ID.get();
        if (value != null) return value;
        String sessionId = SESSION_ID.get();
        if (sessionId != null) {
            RunContext ctx = sessionContextMap.get(sessionId);
            return ctx != null ? ctx.getRunId() : null;
        }
        return null;
    }

    public static String getSessionId() {
        return SESSION_ID.get();
    }

    public static String getUserId() {
        String value = USER_ID.get();
        if (value != null) return value;
        String sessionId = SESSION_ID.get();
        if (sessionId != null) {
            RunContext ctx = sessionContextMap.get(sessionId);
            return ctx != null ? ctx.getUserId() : null;
        }
        return null;
    }

    public static Path getWorkspace() {
        Path value = WORKSPACE.get();
        if (value != null) return value;
        String sessionId = SESSION_ID.get();
        if (sessionId != null) {
            RunContext context = sessionContextMap.get(sessionId);
            return context != null ? context.getWorkspace() : null;
        }
        return null;
    }

    /** 供 ADK 异步线程通过 session ID 恢复上下文 */
    public static void setFromSession(String sessionId) {
        RunContext ctx = sessionContextMap.get(sessionId);
        if (ctx != null) {
            RUN_ID.set(ctx.getRunId());
            SESSION_ID.set(ctx.getSessionId());
            USER_ID.set(ctx.getUserId());
            WORKSPACE.set(ctx.getWorkspace());
        }
    }

    public static void ensureSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        if (sessionId.equals(SESSION_ID.get())) return;
        setFromSession(sessionId);
    }

    public static RunContext getContextBySession(String sessionId) {
        return sessionContextMap.get(sessionId);
    }

    public static void clear() {
        RUN_ID.remove();
        SESSION_ID.remove();
        USER_ID.remove();
        WORKSPACE.remove();
    }

    public static void removeSession(String sessionId) {
        sessionContextMap.remove(sessionId);
    }
}
