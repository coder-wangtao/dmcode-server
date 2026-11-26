package com.wangtao.domain.agent.service.armory.matter.tools.command;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@Profile("local")
public class LocalProcessCommandExecutor implements LocalCommandExecutor {

    private final String workspace;
    private final boolean windows;

    public LocalProcessCommandExecutor(@Value("${dmcode.local.workspace:${user.home}}") String workspace) {
        this.workspace = Path.of(workspace).toAbsolutePath().normalize().toString();
        this.windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    @Override
    public CommandResult execute(String sessionId, String command, String cwd, long timeoutMs) {
        long startedAt = System.currentTimeMillis();
        try {
            var runContext = sessionId != null ? RunContextHolder.getContextBySession(sessionId) : null;
            Path activeWorkspace = runContext != null && runContext.getWorkspace() != null
                    ? runContext.getWorkspace()
                    : Path.of(workspace);
            Path workingDirectory = resolveWorkingDirectory(cwd, activeWorkspace);
            ProcessBuilder processBuilder = new ProcessBuilder(
                    windows ? "cmd.exe" : "/bin/sh",
                    windows ? "/c" : "-lc",
                    command
            );
            processBuilder.directory(workingDirectory.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.environment().putIfAbsent("DMCODE_WORKSPACE", activeWorkspace.toString());

            Process process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            Charset charset = windows ? Charset.defaultCharset() : StandardCharsets.UTF_8;
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), charset))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            outputReader.setDaemon(true);
            outputReader.start();

            boolean finished = process.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return CommandResult.timeout(extractCmdId(sessionId));
            }
            outputReader.join(2000);
            String commandOutput;
            synchronized (output) {
                commandOutput = output.toString();
            }

            int exitCode = process.exitValue();
            return CommandResult.success(
                    extractCmdId(sessionId), sessionId, commandOutput, exitCode,
                    System.currentTimeMillis() - startedAt
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandResult.error(extractCmdId(sessionId), sessionId, "命令执行被中断", System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("[LocalProcessCommandExecutor] 执行失败: command={}", command, e);
            return CommandResult.error(
                    extractCmdId(sessionId), sessionId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    System.currentTimeMillis() - startedAt
            );
        }
    }

    private Path resolveWorkingDirectory(String cwd, Path root) {
        Path current = cwd == null || cwd.isBlank()
                ? root
                : root.resolve(cwd).normalize();
        if (!current.startsWith(root)) {
            throw new IllegalArgumentException("cwd 必须位于工作区内: " + workspace);
        }
        return current;
    }

    private String extractCmdId(String sessionId) {
        return "local_" + System.nanoTime();
    }
}
