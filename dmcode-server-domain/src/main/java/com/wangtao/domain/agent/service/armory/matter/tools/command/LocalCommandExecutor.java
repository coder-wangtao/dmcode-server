package com.wangtao.domain.agent.service.armory.matter.tools.command;

public interface LocalCommandExecutor {

    CommandResult execute(String sessionId, String command, String cwd, long timeoutMs);
}
