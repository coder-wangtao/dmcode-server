package com.wangtao.domain.agent.service.context.provider;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TaskProvider implements ContextProvider {
    @Override public String getName() { return "task"; }
    @Override public int getOrder() { return 20; }
    @Override public boolean enabled() { return true; }

    /** 任务描述的长度上限，避免把超长分析报告整体塞进前缀 */
    private static final int MAX_TASK_DESC_LENGTH = 800;

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();

        if (messageHistory != null && !messageHistory.isEmpty()) {
            // 取「最新」一条用户消息作为当前任务描述（而非第一条）：
            // 「开始处理」「完善这些内容」这类续作指令才是当前要执行的任务，
            // 历史上最初的分析请求只是背景。
            String latestUserMessage = null;
            for (int i = messageHistory.size() - 1; i >= 0; i--) {
                Map<String, Object> msg = messageHistory.get(i);
                if ("user".equals(msg.get("role")) && msg.get("content") != null
                        && !String.valueOf(msg.get("content")).isBlank()) {
                    latestUserMessage = String.valueOf(msg.get("content"));
                    break;
                }
            }
            if (latestUserMessage != null) {
                result.put("taskDescription", truncate(latestUserMessage));
            }

            final String latest = latestUserMessage;
            // 附带最近的用户指令序列（不含当前消息），帮助模型理解「这些内容」指代什么
            List<String> recentUserMessages = messageHistory.stream()
                    .filter(m -> "user".equals(m.get("role")) && m.get("content") != null)
                    .map(m -> String.valueOf(m.get("content")))
                    .filter(c -> !c.isBlank() && !c.equals(latest))
                    .toList();
            if (!recentUserMessages.isEmpty()) {
                String history = recentUserMessages.stream()
                        .map(this::truncate)
                        .collect(Collectors.joining("\n---\n"));
                result.put("priorUserInstructions", history);
            }
        }

        return result;
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() <= MAX_TASK_DESC_LENGTH ? s : s.substring(0, MAX_TASK_DESC_LENGTH) + "…（已截断）";
    }
}
