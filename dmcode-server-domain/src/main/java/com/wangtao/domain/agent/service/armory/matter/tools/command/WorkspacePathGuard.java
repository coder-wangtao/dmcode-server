package com.wangtao.domain.agent.service.armory.matter.tools.command;

import java.nio.file.Path;

public interface WorkspacePathGuard {

    Path resolve(String input);
}
