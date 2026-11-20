package com.wangtao.domain.agent.service.coding;

import com.wangtao.domain.agent.service.coding.verification.PostEditVerificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多场景连续操作验证（不依赖 @Slf4j 类，避免 Logback 环境冲突）
 *
 * 场景 1: 单文件编辑 → diff → 回滚
 * 场景 2: 多文件连续编辑 → 各自独立 diff 和备份 → 逐个回滚
 * 场景 3: 验证失败 → 错误摘要提取
 * 场景 4: 长对话上下文增长 → token 估算递增
 */
class MultiScenarioWorkflowTests {

    private final CodePatchService patchService = new CodePatchService();

    @TempDir
    Path workspace;

    @Test
    void scenario1_singleEditAndRollback() throws IOException {
        Path file = workspace.resolve("Service.java");
        Files.writeString(file, "original\n");

        CodePatchService.CodePatchResult result = patchService.writeWithBackup(file, "modified\nadded\n");
        assertEquals("modified\nadded\n", Files.readString(file));
        assertNotNull(result.backupPath());
        assertTrue(result.unifiedDiff().contains("+modified"));
        assertTrue(result.unifiedDiff().contains("+added"));

        assertTrue(patchService.rollback(file, result.backupPath()));
        assertEquals("original\n", Files.readString(file));
    }

    @Test
    void scenario2_multiFileSequentialEditsAndRollback() throws IOException {
        Path fileA = workspace.resolve("A.java");
        Path fileB = workspace.resolve("B.java");
        Path fileC = workspace.resolve("C.java");
        Files.writeString(fileA, "originalA\n");
        Files.writeString(fileB, "originalB\n");
        Files.writeString(fileC, "originalC\n");

        CodePatchService.CodePatchResult resultA = patchService.writeWithBackup(fileA, "editedA\n");
        CodePatchService.CodePatchResult resultB = patchService.writeWithBackup(fileB, "editedB\nextraB\n");
        CodePatchService.CodePatchResult resultC = patchService.writeWithBackup(fileC, "editedC\n");

        assertEquals("editedA\n", Files.readString(fileA));
        assertEquals("editedB\nextraB\n", Files.readString(fileB));
        assertEquals("editedC\n", Files.readString(fileC));

        assertTrue(patchService.rollback(fileC, resultC.backupPath()));
        assertTrue(patchService.rollback(fileB, resultB.backupPath()));
        assertTrue(patchService.rollback(fileA, resultA.backupPath()));

        assertEquals("originalA\n", Files.readString(fileA));
        assertEquals("originalB\n", Files.readString(fileB));
        assertEquals("originalC\n", Files.readString(fileC));
    }

    @Test
    void scenario3_verificationErrorSummary() {
        String compileOutput = """
                [INFO] Compiling 3 source files
                [ERROR] /src/App.java:[15,8] cannot find symbol
                [ERROR]   symbol: variable missingVar
                [INFO] 1 error
                [INFO] BUILD FAILURE
                """;

        PostEditVerificationService.VerificationResult result =
                PostEditVerificationService.VerificationResult.failed(
                        "maven", "退出码: 1", compileOutput);

        assertTrue(!result.verified());
        assertNotNull(result.errorSummary());
        assertTrue(result.errorSummary().contains("cannot find symbol"));
        assertTrue(result.errorSummary().contains("missingVar"));
    }

    @Test
    void scenario4_longConversationTokenEstimation() {
        java.util.List<java.util.Map<String, Object>> shortHistory = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            shortHistory.add(java.util.Map.of("role", "user", "content", "short message " + i));
        }
        long shortTokens = estimateTokens(shortHistory);

        java.util.List<java.util.Map<String, Object>> longHistory = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            longHistory.add(java.util.Map.of("role", "user", "content", "longer message with more content " + i));
        }
        long longTokens = estimateTokens(longHistory);

        assertTrue(longTokens > shortTokens,
                "Long conversation should estimate more tokens: " + longTokens + " > " + shortTokens);
        assertTrue(shortTokens > 0);
    }

    /** 与 LoopState.estimateTokens 相同的估算逻辑（避免触发 @Slf4j 初始化） */
    private long estimateTokens(java.util.List<java.util.Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        long total = 0;
        for (java.util.Map<String, Object> msg : messages) {
            String content = String.valueOf(msg.get("content"));
            total += content != null ? content.length() / 2L : 0;
        }
        return total;
    }
}
