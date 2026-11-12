package com.wangtao.domain.agent.service.coding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class WorkspaceStatusService {

    private final WorkspaceManager workspaceManager;

    public WorkspaceStatusService(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    public WorkspaceStatus getStatus(String projectRootPath) {
        if (projectRootPath == null || projectRootPath.isBlank()) {
            return WorkspaceStatus.unavailable("未提供项目路径");
        }

        try {
            Path root = Path.of(projectRootPath).toAbsolutePath().normalize();
            WorkspaceManager.WorkspaceState state = workspaceManager.resolve(root);
            if (!state.gitRepository()) {
                return new WorkspaceStatus(root.toString(), null, null, false, "非 Git 仓库");
            }

            String branch = runGit(root, "git", "rev-parse", "--abbrev-ref", "HEAD");
            String status = runGit(root, "git", "status", "--porcelain");
            String dirtySummary = status == null || status.isBlank()
                    ? "工作区干净"
                    : summarizeStatus(status);

            return new WorkspaceStatus(root.toString(), branch, dirtySummary, true, null);
        } catch (Exception e) {
            log.warn("获取工作区状态失败: root={}", projectRootPath, e);
            return WorkspaceStatus.unavailable(e.getMessage());
        }
    }

    private String runGit(Path root, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true);
            Process process = pb.start();
            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) return null;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString().trim();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String summarizeStatus(String porcelainOutput) {
        String[] lines = porcelainOutput.split("\n");
        int modified = 0, added = 0, deleted = 0, untracked = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            String code = line.substring(0, Math.min(2, line.length()));
            if (code.contains("??")) untracked++;
            else if (code.contains("D")) deleted++;
            else if (code.contains("A")) added++;
            else modified++;
        }
        StringBuilder sb = new StringBuilder();
        if (modified > 0) sb.append(modified).append(" modified ");
        if (added > 0) sb.append(added).append(" added ");
        if (deleted > 0) sb.append(deleted).append(" deleted ");
        if (untracked > 0) sb.append(untracked).append(" untracked");
        return sb.toString().trim();
    }

    public record WorkspaceStatus(
            String rootPath,
            String branch,
            String gitStatus,
            boolean gitRepository,
            String error) {

        public static WorkspaceStatus unavailable(String error) {
            return new WorkspaceStatus(null, null, null, false, error);
        }
    }
}
