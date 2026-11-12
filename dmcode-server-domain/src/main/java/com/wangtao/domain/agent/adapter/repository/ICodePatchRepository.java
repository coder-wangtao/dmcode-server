package com.wangtao.domain.agent.adapter.repository;

import com.wangtao.domain.agent.model.entity.CodePatchEntity;
import com.wangtao.domain.agent.model.valobj.patch.CodePatchStatus;

import java.util.List;

public interface ICodePatchRepository {
    void save(CodePatchEntity entity);
    void updateStatus(String patchId, CodePatchStatus status);
    CodePatchEntity findById(String patchId);
    List<CodePatchEntity> findByRunId(String runId);
    List<CodePatchEntity> findBySessionId(String sessionId, int limit);
    List<CodePatchEntity> findUnapprovedBySessionId(String sessionId, int limit);
}
