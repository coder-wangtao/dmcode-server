package com.wangtao.domain.agent.service.chat;

import com.wangtao.domain.agent.model.entity.ChatCommandEntity;
import com.wangtao.domain.agent.adapter.repository.IChatHistoryRepository;
import com.wangtao.domain.agent.model.entity.ChatMessageEntity;
import com.wangtao.domain.agent.model.entity.ChatSessionEntity;
import com.wangtao.domain.agent.model.entity.AgentRunEntity;
import com.wangtao.domain.agent.model.valobj.run.AgentRunStatus;
import com.wangtao.domain.agent.adapter.repository.IAgentRunRepository;
import com.wangtao.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.wangtao.domain.agent.model.valobj.AiAgentRegisterVO;
import com.wangtao.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.wangtao.domain.agent.service.IChatService;
import com.wangtao.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.wangtao.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import com.wangtao.domain.agent.service.armory.matter.mcp.server.SshExecuteMcpService;
import com.wangtao.types.enums.ResponseCode;
import com.wangtao.types.exception.AppException;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private SshExecuteAdkTool sshExecuteAdkTool;
    
    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    @Resource
    private IAgentRunRepository agentRunRepository;



    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }

        return agentList;
    }

    @Override
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();

        // 每次调用都创建新的 ADK session，确保会话隔离
        // sessionKey 作为 sessionId（第4参数），userId 保持原值，确保 runAsync 能找到此 session
        String sessionKey = userId + "_" + System.currentTimeMillis();
        Session session = runner.sessionService().createSession(appName, userId, null, sessionKey)
                .blockingGet();

        // [Phase 5] 保存会话元数据到数据库
        try {
            ChatSessionEntity sessionEntity = ChatSessionEntity.builder()
                    .id(session.id())
                    .agentId(agentId)
                    .userId(userId)
                    .title("新会话")
                    .messageCount(0)
                    .build();
            chatHistoryRepository.saveSession(sessionEntity);
        } catch (Exception e) {
            log.error("保存会话元数据失败", e);
        }

        log.info("创建新 ADK session - agentId:{}, userId:{}, sessionId:{}", agentId, userId, session.id());

        return session.id();
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String sessionId = createSession(agentId, userId);

        return handleMessage(agentId, userId, sessionId, message);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg);

        // 补充运行轨迹（/chat 旧同步路径不走 ReAct 引擎，无 AgentLoopExecutor，这里手动落一条 agent_run）
        long startedAt = System.currentTimeMillis();
        String runId = java.util.UUID.randomUUID().toString();
        List<String> outputs = new ArrayList<>();
        int[] toolCalls = {0};
        AgentRunStatus status = AgentRunStatus.COMPLETED;
        String stopReason = "completed";
        try {
            events.blockingForEach(event -> {
                outputs.add(event.stringifyContent());
                try {
                    toolCalls[0] += (event.functionCalls() == null ? 0 : event.functionCalls().size());
                } catch (Exception ignore) {
                }
            });
        } catch (Exception e) {
            status = AgentRunStatus.FAILED;
            stopReason = "error";
            saveRunRecord(runId, sessionId, userId, agentId, status, stopReason, startedAt, toolCalls[0]);
            throw e;
        }
        saveRunRecord(runId, sessionId, userId, agentId, status, stopReason, startedAt, toolCalls[0]);
        return outputs;
    }

    private void saveRunRecord(String runId, String sessionId, String userId, String agentId,
                               AgentRunStatus status, String stopReason, long startedAt, int totalToolCalls) {
        try {
            java.util.Date now = new java.util.Date();
            agentRunRepository.save(AgentRunEntity.builder()
                    .runId(runId)
                    .sessionId(sessionId)
                    .userId(userId)
                    .agentId(agentId)
                    .status(status)
                    .stopReason(stopReason)
                    .round(0)
                    .totalToolCalls(totalToolCalls)
                    .totalTokens(0L)
                    .startedAt(new java.util.Date(startedAt))
                    .finishedAt(now)
                    .cancelled(false)
                    .contextCompressed(false)
                    .createdAt(new java.util.Date(startedAt))
                    .updatedAt(now)
                    .build());
        } catch (Exception e) {
            log.warn("保存 /chat 运行轨迹失败 runId={}", runId, e);
        }
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        return handleMessageStream(agentId, userId, sessionId, message, null);
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message, String terminalSessionId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        // 设置终端会话ID到ThreadLocal，供 MCP 工具使用
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            log.info("设置终端会话ID: {}", terminalSessionId);
            SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);
            SshExecuteMcpService.setCurrentTerminalSession(terminalSessionId);
        }

        try {
            Content userMsg = Content.fromParts(Part.fromText(message));
            // autoCreateSession=true: 如果 sessionId 不存在，ADK 自动创建
            Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg);

            return events;
        } finally {
        // 清理 ThreadLocal，防止线程池复用泄露
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            SshExecuteAdkTool.clearCurrentTerminalSession();
            SshExecuteMcpService.clearCurrentTerminalSession();
        }
    }
}

    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        List<Part> parts = new ArrayList<>();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        // 获取运行体
        Runner runner = aiAgentRegisterVO.getRunner();

        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), content);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

}
