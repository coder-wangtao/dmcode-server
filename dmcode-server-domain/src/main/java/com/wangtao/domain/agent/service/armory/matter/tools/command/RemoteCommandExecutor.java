package com.wangtao.domain.agent.service.armory.matter.tools.command;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local")
public class RemoteCommandExecutor implements LocalCommandExecutor {

    private final CommandDispatcher commandDispatcher;

    public RemoteCommandExecutor(CommandDispatcher commandDispatcher) {
        this.commandDispatcher = commandDispatcher;
    }

    @Override
    public CommandResult execute(String sessionId, String command, String cwd, long timeoutMs) {
        return commandDispatcher.dispatchAndWait(
                CommandRequest.executeLocal(sessionId, command, cwd, timeoutMs), timeoutMs);
    }
}
