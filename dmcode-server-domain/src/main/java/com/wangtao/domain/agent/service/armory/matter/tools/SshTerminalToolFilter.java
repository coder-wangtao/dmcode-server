package com.wangtao.domain.agent.service.armory.matter.tools;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Callbacks;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * SSH 工具可见性过滤器（ADK beforeModelCallback）
 *
 * <p>背景：Agent 装配时一次性注册了本地工具 + 远程 SSH 工具，但 Agent 是单例缓存的，
 * 无法按会话动态裁剪。结果是：用户没有建立任何 SSH 连接时，模型依然"看得到"
 * executeCommand/readFile/writeFile 等远程工具，纯本地编码场景下误选这些工具会得到
 * 「未绑定 SSH 终端会话」错误，历史上甚至因此烧掉几十步重试。
 *
 * <p>方案：在每次 LLM 请求前检查当前对话会话是否绑定了 SSH 终端会话——
 * 没有绑定时直接从请求的 tool 声明中移除全部远程 SSH 工具，模型根本不知道它们存在；
 * 一旦用户建立了 SSH 终端连接，工具自动恢复可见。
 *
 * <p>注意：此回调只影响「模型能看到的工具声明」，不影响已发出的 functionCall 的执行
 * ——模型凭历史幻觉调用被隐藏工具的情况由工具自身的错误返回兜底。
 *
 * @author walissh dev
 */
@Slf4j
public final class SshTerminalToolFilter implements Callbacks.BeforeModelCallback {

    /**
     * 依赖 SSH 终端会话的远程工具名单（与 AgentNode 注册的远程工具集一致）
     */
    public static final Set<String> SSH_TOOLS = Set.of(
            "executeCommand",
            "readFile", "writeFile", "listFiles", "searchInFiles", "createFile", "deleteFile"
    );

    private static final SshTerminalToolFilter INSTANCE = new SshTerminalToolFilter();

    private SshTerminalToolFilter() {
    }

    public static SshTerminalToolFilter getInstance() {
        return INSTANCE;
    }

    @Override
    public Maybe<LlmResponse> call(CallbackContext callbackContext, LlmRequest.Builder llmRequestBuilder) {
        String chatSessionId = callbackContext.sessionId();
        boolean hasSsh = SshTerminalSessionRegistry.hasTerminalSession(chatSessionId);

        if (hasSsh) {
            return Maybe.empty(); // 已绑定 SSH 会话，工具全量可见
        }

        // Builder.tools() getter 是包私有的，通过 build() 出的实例读取（LlmRequest.tools() 为 public）
        Map<String, BaseTool> tools;
        try {
            tools = llmRequestBuilder.build().tools();
        } catch (Exception e) {
            log.warn("[SshToolFilter] 读取 LlmRequest 工具列表失败，跳过过滤: {}", e.getMessage());
            return Maybe.empty();
        }
        if (tools == null || tools.isEmpty()) {
            return Maybe.empty();
        }

        Map<String, BaseTool> filtered = new LinkedHashMap<>();
        int removed = 0;
        for (Map.Entry<String, BaseTool> entry : tools.entrySet()) {
            if (entry.getValue() != null && SSH_TOOLS.contains(entry.getValue().name())) {
                removed++;
                continue;
            }
            filtered.put(entry.getKey(), entry.getValue());
        }

        if (removed > 0) {
            llmRequestBuilder.tools(filtered);
            log.info("[SshToolFilter] 会话未绑定 SSH 终端，隐藏 {} 个远程工具: session={}, 剩余 {} 个工具",
                    removed, chatSessionId, filtered.size());
        }

        return Maybe.empty();
    }
}
