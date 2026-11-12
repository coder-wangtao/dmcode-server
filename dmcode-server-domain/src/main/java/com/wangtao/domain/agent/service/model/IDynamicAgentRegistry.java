package com.wangtao.domain.agent.service.model;

import com.wangtao.domain.agent.model.valobj.AiAgentRegisterVO;

public interface IDynamicAgentRegistry {

    String registerAgentForModel(String sourceAgentId, Long modelId);

    AiAgentRegisterVO getRuntimeAgent(String runtimeAgentId);

    void evict(Long modelId);
}
