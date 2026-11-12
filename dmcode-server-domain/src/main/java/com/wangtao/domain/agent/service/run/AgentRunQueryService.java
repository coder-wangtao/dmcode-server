package com.wangtao.domain.agent.service.run;

import com.wangtao.domain.agent.adapter.repository.IAgentRunRepository;
import com.wangtao.domain.agent.model.entity.AgentRunEntity;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentRunQueryService {

    @Resource
    private IAgentRunRepository agentRunRepository;

    public AgentRunEntity queryById(String runId) {
        return agentRunRepository.findById(runId);
    }

    public List<AgentRunEntity> queryBySessionId(String sessionId, int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        return agentRunRepository.findBySessionId(sessionId, safeLimit);
    }
}
