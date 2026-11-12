package com.wangtao.config;

import com.wangtao.domain.agent.adapter.repository.IModelConfigRepository;
import com.wangtao.domain.agent.model.entity.ModelConfigEntity;
import com.wangtao.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.wangtao.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

@Slf4j
@Component
public class DefaultModelConfigInitializer implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;
    @Resource
    private IModelConfigRepository modelConfigRepository;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            if (!modelConfigRepository.queryAll().isEmpty()) {
                return;
            }

            AiAgentConfigTableVO table = aiAgentAutoConfigProperties.getTables().values().stream()
                    .findFirst()
                    .orElse(null);
            if (table == null || table.getModule() == null
                    || table.getModule().getAiApi() == null || table.getModule().getChatModel() == null) {
                return;
            }

            modelConfigRepository.insert(ModelConfigEntity.builder()
                    .name("默认模型")
                    .baseUrl(table.getModule().getAiApi().getBaseUrl())
                    .apiKey(table.getModule().getAiApi().getApiKey())
                    .modelName(table.getModule().getChatModel().getModel())
                    .completionsPath(StringUtils.defaultIfBlank(
                            table.getModule().getAiApi().getCompletionsPath(), "v1/chat/completions"))
                    .build());
            log.info("已从默认智能体配置初始化模型配置");
        } catch (Exception e) {
            log.error("初始化默认模型配置失败", e);
        }
    }
}
