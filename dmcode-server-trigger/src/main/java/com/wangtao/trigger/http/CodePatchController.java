package com.wangtao.trigger.http;

import com.wangtao.api.response.Response;
import com.wangtao.domain.agent.model.entity.CodePatchEntity;
import com.wangtao.domain.agent.service.coding.CodePatchAuditService;
import com.wangtao.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

//代码修改审计与回滚”的 HTTP 接口
//Agent 每修改一次文件，系统会生成一条 CodePatchEntity 记录，包含：
//哪次运行、哪个会话产生的修改
//修改了哪个文件
//增加、删除了多少行
//当前审批状态
//创建时间
//Agent 修改文件
//    ↓
//生成 Patch 审计记录
//    ↓
//用户查看修改
//    ├─ approve：接受修改
//    ├─ reject：拒绝并回滚
//    └─ rollback：直接回滚
@Slf4j
@RestController
@RequestMapping("/api/v1/patch")
//是 Agent 改过一个文件后留下的一条审计记录。一次改文件就记一条，用来查看改了什么、事后回滚或确认。
public class CodePatchController {

    @Resource
    private CodePatchAuditService codePatchAuditService;

    //查询单条修改记录
    @GetMapping("/{patchId}")
    public Response<PatchResponse> queryPatch(@PathVariable String patchId) {
        CodePatchEntity entity = codePatchAuditService.findById(patchId);
        if (entity == null) {
            return Response.<PatchResponse>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("Patch 不存在")
                    .build();
        }
        return success(toResponse(entity));
    }

    //查询某次 Agent 运行产生的全部修改
    @GetMapping("/run/{runId}")
    public Response<List<PatchResponse>> queryByRun(@PathVariable String runId) {
        List<PatchResponse> patches = codePatchAuditService.findByRunId(runId)
                .stream().map(this::toResponse).collect(Collectors.toList());
        return success(patches);
    }

    //查询某个会话的修改历史
    @GetMapping("/session/{sessionId}")
    public Response<List<PatchResponse>> queryBySession(
            @PathVariable String sessionId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<PatchResponse> patches = codePatchAuditService.findBySessionId(sessionId, limit)
                .stream().map(this::toResponse).collect(Collectors.toList());
        return success(patches);
    }

    //询尚未审批的修改
    @GetMapping("/session/{sessionId}/unapproved")
    public Response<List<PatchResponse>> queryUnapproved(
            @PathVariable String sessionId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<PatchResponse> patches = codePatchAuditService.findUnapprovedBySessionId(sessionId, limit)
                .stream().map(this::toResponse).collect(Collectors.toList());
        return success(patches);
    }

    //回滚单次文件修改
    @PostMapping("/{patchId}/rollback")
    public Response<String> rollback(@PathVariable String patchId) {
        boolean success = codePatchAuditService.rollbackPatch(patchId);
        return Response.<String>builder()
                .code(success ? ResponseCode.SUCCESS.getCode() : ResponseCode.UN_ERROR.getCode())
                .info(success ? "已回滚" : "回滚失败（不存在或状态不允许）")
                .data(success ? "ok" : null)
                .build();
    }

    //回滚某次运行的全部修改
    @PostMapping("/run/{runId}/rollback")
    public Response<String> rollbackRun(@PathVariable String runId) {
        int rolled = codePatchAuditService.rollbackRun(runId);
        return Response.<String>builder()
                .code(rolled > 0 ? ResponseCode.SUCCESS.getCode() : ResponseCode.UN_ERROR.getCode())
                .info("批量回滚 " + rolled + " 个文件")
                .data(String.valueOf(rolled))
                .build();
    }

    //确认并接受修改
    @PostMapping("/{patchId}/approve")
    public Response<String> approve(@PathVariable String patchId) {
        codePatchAuditService.updateStatus(patchId, com.wangtao.domain.agent.model.valobj.patch.CodePatchStatus.APPROVED);
        return success("ok");
    }

    //拒绝修改，并尝试回滚文件
    @PostMapping("/{patchId}/reject")
    public Response<String> reject(@PathVariable String patchId) {
        boolean rolled = codePatchAuditService.rollbackPatch(patchId);
        if (rolled) {
            codePatchAuditService.updateStatus(patchId, com.wangtao.domain.agent.model.valobj.patch.CodePatchStatus.REJECTED);
        }
        return Response.<String>builder()
                .code(rolled ? ResponseCode.SUCCESS.getCode() : ResponseCode.UN_ERROR.getCode())
                .info(rolled ? "已拒绝并回滚" : "拒绝失败")
                .build();
    }

    private PatchResponse toResponse(CodePatchEntity entity) {
        PatchResponse r = new PatchResponse();
        r.setPatchId(entity.getPatchId());
        r.setRunId(entity.getRunId());
        r.setSessionId(entity.getSessionId());
        r.setFilePath(entity.getFilePath());
        r.setStatus(entity.getStatus() != null ? entity.getStatus().name() : null);
        r.setLinesAdded(entity.getLinesAdded());
        r.setLinesRemoved(entity.getLinesRemoved());
        r.setCreatedAt(entity.getCreatedAt());
        return r;
    }

    private <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    @lombok.Data
    public static class PatchResponse {
        private String patchId;
        private String runId;
        private String sessionId;
        private String filePath;
        private String status;
        private Integer linesAdded;
        private Integer linesRemoved;
        private java.util.Date createdAt;
    }
}
