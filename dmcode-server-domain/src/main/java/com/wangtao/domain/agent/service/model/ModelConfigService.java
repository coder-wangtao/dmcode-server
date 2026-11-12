package com.wangtao.domain.agent.service.model;

import com.wangtao.domain.agent.adapter.repository.IModelConfigRepository;
import com.wangtao.domain.agent.model.entity.ModelConfigEntity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ModelConfigService {

    private static final String DEFAULT_COMPLETIONS_PATH = "v1/chat/completions";

    @Resource
    private IModelConfigRepository modelConfigRepository;

    public List<ModelConfigEntity> list() {
        return modelConfigRepository.queryAll();
    }

    public ModelConfigEntity save(ModelConfigEntity request) {
        validate(request);

        if (request.getId() == null) {
            request.setBaseUrl(normalizeBaseUrl(request.getBaseUrl()));
            request.setCompletionsPath(normalizeCompletionsPath(request.getCompletionsPath()));
            return modelConfigRepository.insert(request);
        }

        ModelConfigEntity existing = modelConfigRepository.queryById(request.getId());
        if (existing == null) {
            throw new ModelConfigException("模型配置不存在: " + request.getId());
        }

        if (StringUtils.isBlank(request.getApiKey())) {
            request.setApiKey(existing.getApiKey());
        }
        request.setBaseUrl(normalizeBaseUrl(request.getBaseUrl()));
        request.setCompletionsPath(normalizeCompletionsPath(request.getCompletionsPath()));
        modelConfigRepository.update(request);
        return modelConfigRepository.queryById(request.getId());
    }

    public void delete(Long id) {
        if (id == null || modelConfigRepository.queryById(id) == null) {
            throw new ModelConfigException("模型配置不存在: " + id);
        }
        modelConfigRepository.deleteById(id);
    }

    public String revealApiKey(Long id) {
        if (id == null) {
            throw new ModelConfigException("模型配置 ID 不能为空");
        }
        ModelConfigEntity config = modelConfigRepository.queryById(id);
        if (config == null) {
            throw new ModelConfigException("模型配置不存在: " + id);
        }
        return StringUtils.defaultString(config.getApiKey());
    }

    public String test(ModelConfigEntity request) {
        if (request.getId() != null && StringUtils.isBlank(request.getApiKey())) {
            ModelConfigEntity existing = modelConfigRepository.queryById(request.getId());
            if (existing == null) {
                throw new ModelConfigException("模型配置不存在: " + request.getId());
            }
            request.setApiKey(existing.getApiKey());
        }
        validate(request);

        String endpoint = completionEndpoint(request.getBaseUrl(), request.getCompletionsPath());
        Map<String, Object> body = new HashMap<>();
        body.put("model", request.getModelName());
        body.put("messages", List.of(Map.of("role", "user", "content", "ping")));
        body.put("max_tokens", 1);
        body.put("stream", false);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(20_000);

        try {
            String response = RestClient.builder()
                    .requestFactory(requestFactory)
                    .build()
                    .post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + request.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return StringUtils.defaultIfBlank(response, "连接成功");
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new ModelConfigException("模型连接失败: " + message, e);
        }
    }

    private void validate(ModelConfigEntity request) {
        if (request == null || StringUtils.isBlank(request.getName())) {
            throw new ModelConfigException("模型名称不能为空");
        }
        if (StringUtils.isBlank(request.getBaseUrl())) {
            throw new ModelConfigException("API Base URL 不能为空");
        }
        if (StringUtils.isBlank(request.getModelName())) {
            throw new ModelConfigException("模型 ID 不能为空");
        }
        if (request.getId() == null && StringUtils.isBlank(request.getApiKey())) {
            throw new ModelConfigException("API Key 不能为空");
        }
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value.trim();
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private String normalizeCompletionsPath(String value) {
        return StringUtils.defaultIfBlank(StringUtils.trimToNull(value), DEFAULT_COMPLETIONS_PATH);
    }

    private String completionEndpoint(String baseUrl, String completionsPath) {
        String normalizedBase = normalizeBaseUrl(baseUrl);
        String path = normalizeCompletionsPath(completionsPath);
        return path.startsWith("/") ? normalizedBase.substring(0, normalizedBase.length() - 1) + path : normalizedBase + path;
    }
}
