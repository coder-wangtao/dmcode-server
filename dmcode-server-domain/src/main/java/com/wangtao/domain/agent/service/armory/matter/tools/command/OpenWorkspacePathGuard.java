package com.wangtao.domain.agent.service.armory.matter.tools.command;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@Profile("!local")
public class OpenWorkspacePathGuard implements WorkspacePathGuard {

    @Override
    public Path resolve(String input) {
        return Paths.get(input);
    }
}
