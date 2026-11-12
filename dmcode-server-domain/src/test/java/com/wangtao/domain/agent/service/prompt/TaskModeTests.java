package com.wangtao.domain.agent.service.prompt;

import cn.bugstack.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import cn.bugstack.ai.domain.agent.model.valobj.prompt.TaskModeVO;
import cn.bugstack.ai.domain.agent.service.prompt.dynamic.DynamicPromptBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskModeTests {

    private final TaskModeClassifier classifier = new TaskModeClassifier();
    private final DynamicPromptBuilder promptBuilder = new DynamicPromptBuilder();

    @Test
    void classifiesStatusQuestionAsReadOnlyReport() {
        assertEquals(TaskModeVO.STATUS, classifier.classify("README 文档完善了吗", "上一轮修改了代码。"));
        assertEquals(TaskModeVO.STATUS, classifier.classify("这个接口实现，还欠缺什么", "接口分析如下。"));
    }

    @Test
    void classifiesContinuationAsExecute() {
        assertEquals(TaskModeVO.EXECUTE, classifier.classify("开始完善", "前面列出了缺失项。"));
        assertEquals(TaskModeVO.EXECUTE, classifier.classify("按建议处理", "前面列出了修复方案。"));
    }

    @Test
    void classifiesAmbiguousTaskAsClarify() {
        assertEquals(TaskModeVO.CLARIFY, classifier.classify("帮我部署一下", null));
    }

    @Test
    void injectsDeterministicModeIntoPrompt() {
        PromptContextVO context = PromptContextVO.builder().taskMode(TaskModeVO.STATUS).build();
        String prompt = promptBuilder.buildMessagePrefix(context);

        assertTrue(prompt.contains("[本轮任务模式]"));
        assertTrue(prompt.contains("禁止写入"));
    }
}
