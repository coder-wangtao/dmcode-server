package com.wangtao.domain.agent.service.armory.matter.tools.command;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@Profile("local")
public class LocalWorkspacePathGuard implements WorkspacePathGuard {

    private final Path workspace;

    public LocalWorkspacePathGuard(@Value("${dmcode.local.workspace:${user.home}}") String workspace) {
        this.workspace = Paths.get(workspace).toAbsolutePath().normalize();
    }

    @Override
    public Path resolve(String input) {
        Path activeWorkspace = RunContextHolder.getWorkspace() != null
                ? RunContextHolder.getWorkspace()
                : workspace;
        if (input == null || input.isBlank()) {
            return activeWorkspace;
        }
        Path path = activeWorkspace.resolve(input).normalize();
        if (!path.startsWith(activeWorkspace)) {
            throw new IllegalArgumentException("路径必须位于工作区内: " + activeWorkspace);
        }
        return path;
    }
}
