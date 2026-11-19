package com.wangtao.domain.agent.service.armory.matter.tools;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 会话级 SSH 终端绑定注册中心
 *
 * <p>记录「对话会话 → SSH 终端会话」的绑定关系，供两处使用：
 * <ul>
 *   <li>{@code SshTerminalToolFilter}（beforeModelCallback）—— 判断本轮请求是否应该
 *       向模型暴露远程 SSH 工具（未绑定终端会话时隐藏远程工具，从根上杜绝
 *       模型在纯本地场景误用 readFile/executeCommand 等 SSH 工具）；</li>
 *   <li>工具执行时的 terminalSessionId 解析兜底。</li>
 * </ul>
 *
 * <p>写入时机：AiCallNode 每轮绑定终端会话时调用 {@link #bind}；
 * 解绑时机：UserFeedbackNode 会话结束时调用 {@link #unbind}。
 *
 * 
 */
public final class SshTerminalSessionRegistry {

    /** chatSessionId -> terminalSessionId */
    private static final Map<String, String> BINDINGS = new ConcurrentHashMap<>();

    private SshTerminalSessionRegistry() {
    }

    public static void bind(String chatSessionId, String terminalSessionId) {
        if (chatSessionId != null && !chatSessionId.isBlank()
                && terminalSessionId != null && !terminalSessionId.isBlank()) {
            BINDINGS.put(chatSessionId, terminalSessionId);
        }
    }

    public static void unbind(String chatSessionId) {
        if (chatSessionId != null) {
            BINDINGS.remove(chatSessionId);
        }
    }

    public static String getTerminalSessionId(String chatSessionId) {
        return chatSessionId != null ? BINDINGS.get(chatSessionId) : null;
    }

    /**
     * 该对话会话当前是否绑定了 SSH 终端会话。
     * 未绑定时，远程 SSH 工具（executeCommand/readFile/writeFile/listFiles/
     * searchInFiles/createFile/deleteFile）应从模型工具列表中隐藏。
     */
    public static boolean hasTerminalSession(String chatSessionId) {
        return chatSessionId != null && BINDINGS.containsKey(chatSessionId);
    }
}
