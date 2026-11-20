package com.wangtao.domain.agent.service.tools;

import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.wangtao.domain.agent.service.armory.matter.tools.SshTerminalSessionRegistry;
import com.wangtao.domain.agent.service.armory.matter.tools.SshTerminalToolFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSH 工具可见性过滤器测试
 */
class SshTerminalToolFilterTest {

    private final SshTerminalToolFilter filter = SshTerminalToolFilter.getInstance();

    @AfterEach
    void cleanup() {
        SshTerminalSessionRegistry.unbind("session-a");
    }

    private static BaseTool namedTool(String name) {
        return new BaseTool(name, "test tool " + name) {
        };
    }

    private static LlmRequest.Builder requestWithTools(String... names) {
        Map<String, BaseTool> tools = new LinkedHashMap<>();
        for (String name : names) {
            tools.put(name, namedTool(name));
        }
        return LlmRequest.builder().model("test-model").tools(tools);
    }

    private static int sshToolCount(LlmRequest request) {
        return (int) request.tools().values().stream()
                .filter(t -> SshTerminalToolFilter.SSH_TOOLS.contains(t.name()))
                .count();
    }

    @Test
    void hidesSshToolsWhenNoTerminalSessionBound() {
        LlmRequest.Builder builder = requestWithTools(
                "executeCommand", "readFile", "writeLocalFile", "readLocalFile", "executeLocalCommand", "listFiles");

        filter.call(new StubCallbackContext("session-a"), builder);

        LlmRequest result = builder.build();
        assertEquals(0, sshToolCount(result), "未绑定 SSH 终端时远程工具应全部隐藏");
        assertEquals(3, result.tools().size(), "本地工具应保留");
        assertTrue(result.tools().containsKey("readLocalFile"));
        assertTrue(result.tools().containsKey("writeLocalFile"));
        assertTrue(result.tools().containsKey("executeLocalCommand"));
    }

    @Test
    void keepsSshToolsWhenTerminalSessionBound() {
        SshTerminalSessionRegistry.bind("session-a", "terminal-1");

        LlmRequest.Builder builder = requestWithTools("readFile", "readLocalFile");

        filter.call(new StubCallbackContext("session-a"), builder);

        LlmRequest result = builder.build();
        assertEquals(1, sshToolCount(result), "已绑定 SSH 终端时远程工具应保持可见");
        assertEquals(2, result.tools().size());
    }

    @Test
    void rebindingRestoresVisibility() {
        LlmRequest.Builder first = requestWithTools("readFile", "readLocalFile");
        filter.call(new StubCallbackContext("session-a"), first);
        assertEquals(1, first.build().tools().size());

        // 用户建立 SSH 连接后重新绑定
        SshTerminalSessionRegistry.bind("session-a", "terminal-1");

        LlmRequest.Builder second = requestWithTools("readFile", "readLocalFile");
        filter.call(new StubCallbackContext("session-a"), second);
        assertEquals(2, second.build().tools().size(), "绑定后远程工具应恢复可见");
    }

    @Test
    void unknownSessionIdKeepsLocalToolsOnly() {
        LlmRequest.Builder builder = requestWithTools("readLocalFile", "executeCommand");
        filter.call(new StubCallbackContext("never-bound-session"), builder);
        assertFalse(builder.build().tools().containsKey("executeCommand"));
    }

    /** 最小 CallbackContext 桩：仅提供 sessionId */
    private static final class StubCallbackContext extends com.google.adk.agents.CallbackContext {
        private final String sessionId;

        StubCallbackContext(String sessionId) {
            // CallbackContext 构造时会调用 invocationContext.session()，必须传入带 session 的真实 InvocationContext；
            // InvocationContext.build() 校验 agent/sessionService 非空，用最小桩满足校验
            super(com.google.adk.agents.InvocationContext.builder()
                            .invocationId("test-invocation")
                            .session(com.google.adk.sessions.Session.builder(sessionId).build())
                            .sessionService(new com.google.adk.sessions.InMemorySessionService())
                            .agent(new com.google.adk.agents.BaseAgent("stub", "stub", null, null, null) {
                                @Override
                                protected io.reactivex.rxjava3.core.Flowable<com.google.adk.events.Event> runAsyncImpl(com.google.adk.agents.InvocationContext ctx) {
                                    return io.reactivex.rxjava3.core.Flowable.empty();
                                }

                                @Override
                                protected io.reactivex.rxjava3.core.Flowable<com.google.adk.events.Event> runLiveImpl(com.google.adk.agents.InvocationContext ctx) {
                                    return io.reactivex.rxjava3.core.Flowable.empty();
                                }
                            })
                            .build(),
                    com.google.adk.events.EventActions.builder().build());
            this.sessionId = sessionId;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }
    }
}
