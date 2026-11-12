package com.wangtao.domain.agent.service;

import com.wangtao.domain.agent.model.valobj.intent.IntentResultVO;

import java.util.List;

public interface IIntentService {
    IntentResultVO classify(String sessionId, String userId, String message);

    /**
     * 指定模型分类：modelId 优先透传到 LLM 意图分类器，走用户配置的模型。
     */
    IntentResultVO classify(String sessionId, String userId, String message, Long modelId);

    /**
     * 带最近对话内容的分类：LLM 意图分类器可以看到本会话最近的用户消息，
     * 从而正确解析「开始完善」「按上面的处理」等指代上文的执行指令。
     *
     * @param recentUserMessages 本会话最近的用户消息（不含当前消息，按时间先后），可为 null
     */
    IntentResultVO classify(String sessionId, String userId, String message, Long modelId,
                            List<String> recentUserMessages);
}
