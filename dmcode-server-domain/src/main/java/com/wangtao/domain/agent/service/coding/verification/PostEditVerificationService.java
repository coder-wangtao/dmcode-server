package com.wangtao.domain.agent.service.coding.verification;

import com.wangtao.domain.agent.service.coding.WorkspaceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PostEditVerificationService {

    private final VerificationEngine verificationEngine;
    private final WorkspaceManager workspaceManager;

    public PostEditVerificationService(VerificationEngine verificationEngine,
                                        WorkspaceManager workspaceManager) {
        this.verificationEngine = verificationEngine;
        this.workspaceManager = workspaceManager;
    }

    public VerificationResult verifyAfterEdit(String projectRootPath) {
        if (projectRootPath == null || projectRootPath.isBlank()) {
            return VerificationResult.skipped("未提供项目路径");
        }

        try {
            Path root = Path.of(projectRootPath).toAbsolutePath().normalize();
            workspaceManager.resolve(root);
            VerificationPlan plan = verificationEngine.plan(root);

            if ("unknown".equals(plan.projectType()) || plan.commands().isEmpty()) {
                return VerificationResult.skipped("未识别到项目类型，跳过自动验证");
            }

            ProcessBuilder pb = new ProcessBuilder(plan.commands())
                    .directory(root.toFile())
                    .redirectErrorStream(true);
            pb.environment().putIfAbsent("DMCODE_WORKSPACE", projectRootPath);

            Process process = pb.start();
            List<String> outputLines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < 200) {
                    outputLines.add(line);
                    lineCount++;
                }
            }

            boolean completed = process.waitFor(120, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return VerificationResult.failed(plan.projectType(), "验证超时 (120s)", truncate(outputLines));
            }

            int exitCode = process.exitValue();
            String output = truncate(outputLines);
            if (exitCode == 0) {
                return VerificationResult.passed(plan.projectType(), output);
            } else {
                return VerificationResult.failed(plan.projectType(), "退出码: " + exitCode, output);
            }
        } catch (Exception e) {
            log.error("自动验证执行异常: root={}", projectRootPath, e);
            return VerificationResult.failed("unknown", e.getMessage(), "");
        }
    }

    private String truncate(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() + line.length() > 5000) {
                sb.append("\n... (输出已截断)");
                break;
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    public record VerificationResult(
            boolean verified,
            String projectType,
            String message,
            String output,
            String errorSummary,
            boolean skipped) {

        public static VerificationResult passed(String projectType, String output) {
            return new VerificationResult(true, projectType, "验证通过", output, null, false);
        }

        public static VerificationResult failed(String projectType, String message, String output) {
            String summary = extractErrorSummary(output);
            return new VerificationResult(false, projectType, message, output, summary, false);
        }

        public static VerificationResult skipped(String reason) {
            return new VerificationResult(false, "unknown", reason, "", null, true);
        }

        private static String extractErrorSummary(String output) {
            if (output == null || output.isBlank()) return null;
            List<String> errorLines = new ArrayList<>();
            String[] lines = output.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (errorLines.size() >= 30) break;
                String lower = trimmed.toLowerCase();
                if (trimmed.startsWith("[ERROR]")
                        || lower.contains("error:")
                        || lower.contains("cannot find symbol")
                        || lower.contains("找不到符号")
                        || lower.contains("程序包不存在")
                        || lower.contains("execution failed for task")
                        || lower.contains("what went wrong:")
                        || lower.contains("compilation failed")
                        || lower.contains("build failed")
                        || lower.contains("npm err!")) {
                    errorLines.add(trimmed);
                }
            }
            return errorLines.isEmpty() ? null : String.join("\n", errorLines);
        }
    }
}
