package com.wangtao.domain.agent.service.intent;

import com.wangtao.domain.agent.model.valobj.intent.ConversationContextVO;
import com.wangtao.domain.agent.model.valobj.intent.IntentResultVO;

import java.util.List;

public interface IIntentClassifier {
    IntentResultVO classify(String message, ConversationContextVO context);

    /**
     * 指定模型分类：优先使用用户配置的模型（modelId），未配置时由实现类回退默认模型。
     */
    IntentResultVO classify(String message, ConversationContextVO context, Long modelId);

    /**
     * 带最近对话内容的分类：供 LLM 分类器做指代消解。
     * recentUserMessages 为本会话最近的用户消息（不含当前消息，按时间先后），可为 null。
     */
    IntentResultVO classify(String message, ConversationContextVO context, Long modelId,
                            List<String> recentUserMessages);
}
