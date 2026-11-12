package com.wangtao.domain.agent.model.entity;

import com.wangtao.domain.agent.model.valobj.run.AgentRunStatus;
import com.wangtao.domain.agent.service.engine.LoopState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentRunEntity {

    private String runId;
    private String sessionId;
    private String userId;
    private String agentId;
    private AgentRunStatus status;
    private String stopReason;
    private Integer round;
    private Integer totalToolCalls;
    private Long totalTokens;
    private Date startedAt;
    private Date finishedAt;
    private Boolean cancelled;
    private String cancelReason;
    private Boolean contextCompressed;
    private Integer maxRounds;
    private Integer maxToolCallsPerRound;
    private Integer maxAiRetries;
    private Long idleTimeoutMs;
    private Integer maxTokenBudget;
    private Date createdAt;
    private Date updatedAt;

    public static AgentRunEntity from(LoopState state) {
        return AgentRunEntity.builder()
                .runId(state.getRunId())
                .sessionId(state.getSessionId())
                .userId(state.getUserId())
                .agentId(state.getAgentId())
                .status(resolveStatus(state))
                .stopReason(state.getStopReason())
                .round(state.getRound())
                .totalToolCalls(state.getTotalToolCalls())
                .totalTokens(state.getTotalTokens())
                .startedAt(toDate(state.getStartedAt()))
                .finishedAt(toDate(state.getFinishedAt()))
                .cancelled(state.isCancelled())
                .cancelReason(state.getCancelReason())
                .contextCompressed(state.isContextCompressed())
                .maxRounds(state.getConfig().getMaxRounds())
                .maxToolCallsPerRound(state.getConfig().getMaxToolCallsPerRound())
                .maxAiRetries(state.getConfig().getMaxAiRetries())
                .idleTimeoutMs(state.getConfig().getIdleTimeoutMs())
                .maxTokenBudget(state.getConfig().getMaxTokenBudget())
                .createdAt(toDate(state.getStartedAt()))
                .updatedAt(toDate(state.getFinishedAt() > 0 ? state.getFinishedAt() : System.currentTimeMillis()))
                .build();
    }

    private static AgentRunStatus resolveStatus(LoopState state) {
        if (state.isCancelled()) return AgentRunStatus.CANCELLED;
        if ("error".equals(state.getStopReason()) || "compress_failed".equals(state.getStopReason())) {
            return AgentRunStatus.FAILED;
        }
        if (state.getStopReason() != null) return AgentRunStatus.COMPLETED;
        // 兜底：已设置结束时间但 stopReason 缺失（如个别路径未同步），按已完成处理，避免停留 RUNNING
        if (state.getFinishedAt() > 0) return AgentRunStatus.COMPLETED;
        return AgentRunStatus.RUNNING;
    }

    private static Date toDate(long timestamp) {
        return new Date(timestamp);
    }
}
