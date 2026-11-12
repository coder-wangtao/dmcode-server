package com.wangtao.domain.agent.service.tools.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RunContextHolderTests {

    @Test
    void threadLocalSetAndGet() {
        RunContextHolder.set("run-1", "session-1", "user-1", false);

        assertEquals("run-1", RunContextHolder.getRunId());
        assertEquals("session-1", RunContextHolder.getSessionId());
        assertEquals("user-1", RunContextHolder.getUserId());

        RunContextHolder.clear();
    }

    @Test
    void clearRemovesThreadLocalButSessionMapRetains() {
        RunContextHolder.set("run-2", "session-2", "user-2", true);
        RunContextHolder.clear();

        assertNull(RunContextHolder.getRunId());
        assertNull(RunContextHolder.getUserId());

        RunContextHolder.RunContext ctx = RunContextHolder.getContextBySession("session-2");
        assertEquals("run-2", ctx.getRunId());
        assertEquals("user-2", ctx.getUserId());

        RunContextHolder.removeSession("session-2");
        assertNull(RunContextHolder.getContextBySession("session-2"));
    }

    @Test
    void setFromSessionRestoresContext() {
        RunContextHolder.set("run-3", "session-3", "user-3", false);
        RunContextHolder.clear();

        RunContextHolder.setFromSession("session-3");
        assertEquals("run-3", RunContextHolder.getRunId());
        assertEquals("session-3", RunContextHolder.getSessionId());
        assertEquals("user-3", RunContextHolder.getUserId());

        RunContextHolder.clear();
        RunContextHolder.removeSession("session-3");
    }

    @Test
    void ensureSessionReplacesStaleToolThreadContext() {
        RunContextHolder.set("run-stale", "session-stale", "user-stale", false,
                java.nio.file.Path.of("/tmp/session-stale"));
        RunContextHolder.set("run-current", "session-current", "user-current", false,
                java.nio.file.Path.of("/tmp/session-current"));

        RunContextHolder.ensureSession("session-stale");
        assertEquals("session-stale", RunContextHolder.getSessionId());
        assertEquals(java.nio.file.Path.of("/tmp/session-stale"), RunContextHolder.getWorkspace());

        RunContextHolder.ensureSession("session-current");
        assertEquals("session-current", RunContextHolder.getSessionId());
        assertEquals(java.nio.file.Path.of("/tmp/session-current"), RunContextHolder.getWorkspace());

        RunContextHolder.clear();
        RunContextHolder.removeSession("session-stale");
        RunContextHolder.removeSession("session-current");
    }

    @Test
    void unknownSessionReturnsNull() {
        assertNull(RunContextHolder.getContextBySession("nonexistent-session"));
    }
}
