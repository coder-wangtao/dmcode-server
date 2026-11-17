package com.wangtao.api;

import com.wangtao.api.dto.*;
import com.wangtao.api.response.Response;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;

/**
 * 智能体服务接口
 * @author wt
 */
public interface IAgentService {

    Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList();

    Response<CreateSessionResponseDTO> createSession(CreateSessionRequestDTO requestDTO);

    Response<ChatResponseDTO> chat(ChatRequestDTO requestDTO);

    ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO);

    Response<AgentRunResponseDTO> queryAgentRun(String runId);

    Response<List<AgentRunResponseDTO>> queryAgentRunsBySession(String sessionId, int limit);

}
