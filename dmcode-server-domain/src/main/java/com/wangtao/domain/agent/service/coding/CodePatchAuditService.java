package com.wangtao.domain.agent.service.coding;

import com.wangtao.domain.agent.adapter.repository.ICodePatchRepository;
import com.wangtao.domain.agent.model.entity.CodePatchEntity;
import com.wangtao.domain.agent.model.valobj.patch.CodePatchStatus;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

@Slf4j
@Service
public class CodePatchAuditService {

    @Resource
    private ICodePatchRepository codePatchRepository;

    @Resource
    private CodePatchService codePatchService;

    public CodePatchAuditService() {}

    public CodePatchAuditService(ICodePatchRepository codePatchRepository, CodePatchService codePatchService) {
        this.codePatchRepository = codePatchRepository;
        this.codePatchService = codePatchService;
    }

    public CodePatchEntity recordPatch(
            String runId, String sessionId, String userId,
            String filePath, String backupPath, String unifiedDiff) {

        String[] diffStats = countDiffLines(unifiedDiff);

        CodePatchEntity entity = CodePatchEntity.builder()
                .patchId(UUID.randomUUID().toString())
                .runId(runId)
                .sessionId(sessionId)
                .userId(userId)
                .filePath(filePath)
                .backupPath(backupPath)
                .unifiedDiff(unifiedDiff)
                .status(CodePatchStatus.APPLIED)
                .linesAdded(Integer.parseInt(diffStats[0]))
                .linesRemoved(Integer.parseInt(diffStats[1]))
                .build();

        codePatchRepository.save(entity);
        log.info("Patch 审计记录已保存: patchId={}, file={}, +{} -{}",
                entity.getPatchId(), filePath, diffStats[0], diffStats[1]);
        return entity;
    }

    public boolean rollbackPatch(String patchId) {
        CodePatchEntity entity = codePatchRepository.findById(patchId);
        if (entity == null) return false;
        if (entity.getStatus() != CodePatchStatus.APPLIED && entity.getStatus() != CodePatchStatus.APPROVED) {
            return false;
        }

        try {
            java.nio.file.Path target = java.nio.file.Path.of(entity.getFilePath());
            boolean restored = codePatchService.rollback(target, entity.getBackupPath());
            if (restored) {
                codePatchRepository.updateStatus(patchId, CodePatchStatus.ROLLED_BACK);
                log.info("Patch 已回滚: patchId={}", patchId);
                return true;
            }
        } catch (Exception e) {
            log.error("Patch 回滚失败: patchId={}", patchId, e);
        }
        return false;
    }

    public void updateStatus(String patchId, CodePatchStatus status) {
        codePatchRepository.updateStatus(patchId, status);
    }

    public CodePatchEntity findById(String patchId) {
        return codePatchRepository.findById(patchId);
    }

    public List<CodePatchEntity> findByRunId(String runId) {
        return codePatchRepository.findByRunId(runId);
    }

    public List<CodePatchEntity> findBySessionId(String sessionId, int limit) {
        return codePatchRepository.findBySessionId(sessionId, limit);
    }

    public List<CodePatchEntity> findUnapprovedBySessionId(String sessionId, int limit) {
        return codePatchRepository.findUnapprovedBySessionId(sessionId, limit);
    }

    /**
     * 批量回滚一个 run 内所有已应用的 patch（按创建时间倒序回滚，保证依赖顺序正确）
     *
     * @return 回滚成功的 patch 数量
     */
    public int rollbackRun(String runId) {
        List<CodePatchEntity> patches = codePatchRepository.findByRunId(runId);
        int rolledBack = 0;
        for (CodePatchEntity patch : patches) {
            if (patch.getStatus() == com.wangtao.domain.agent.model.valobj.patch.CodePatchStatus.APPLIED
                    || patch.getStatus() == com.wangtao.domain.agent.model.valobj.patch.CodePatchStatus.APPROVED) {
                if (rollbackPatch(patch.getPatchId())) {
                    rolledBack++;
                }
            }
        }
        log.info("Run 批量回滚完成: runId={}, total={}, rolledBack={}", runId, patches.size(), rolledBack);
        return rolledBack;
    }

    private String[] countDiffLines(String diff) {
        int added = 0;
        int removed = 0;
        if (diff != null) {
            for (String line : diff.split("\n")) {
                if (line.startsWith("+") && !line.startsWith("+++")) added++;
                if (line.startsWith("-") && !line.startsWith("---")) removed++;
            }
        }
        return new String[]{String.valueOf(added), String.valueOf(removed)};
    }
}
