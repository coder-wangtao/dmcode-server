package com.wangtao.domain.agent.model.entity;

import com.wangtao.domain.agent.model.valobj.patch.CodePatchStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodePatchEntity {
    private String patchId;
    private String runId;
    private String sessionId;
    private String userId;
    private String filePath;
    private String backupPath;
    private String unifiedDiff;
    private CodePatchStatus status;
    private Integer linesAdded;
    private Integer linesRemoved;
    private Date createdAt;
    private Date updatedAt;
}
