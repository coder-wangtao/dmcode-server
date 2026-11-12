package com.wangtao.domain.agent.model.valobj.prompt;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TaskModeVO {
    EXECUTE("执行任务"),
    STATUS("确认或查询状态"),
    CLARIFY("需要澄清"),
    CONVERSATIONAL("普通问答");

    private final String label;
}
