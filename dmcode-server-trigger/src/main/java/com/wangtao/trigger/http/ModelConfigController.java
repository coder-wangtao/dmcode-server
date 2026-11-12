package com.wangtao.trigger.http;

import com.wangtao.api.dto.ModelConfigRequestDTO;
import com.wangtao.api.dto.ModelConfigResponseDTO;
import com.wangtao.api.response.Response;
import com.wangtao.domain.agent.model.entity.ModelConfigEntity;
import com.wangtao.domain.agent.service.model.IDynamicAgentRegistry;
import com.wangtao.domain.agent.service.model.ModelConfigException;
import com.wangtao.domain.agent.service.model.ModelConfigService;
import com.wangtao.domain.agent.service.model.UserModelChatModelFactory;
import com.wangtao.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/model")
@CrossOrigin(origins = "*")
public class ModelConfigController {

    @Resource
    private ModelConfigService modelConfigService;
    @Resource
    private IDynamicAgentRegistry dynamicAgentRegistry;
    @Resource
    private UserModelChatModelFactory userModelChatModelFactory;

    @GetMapping("list")
    public Response<List<ModelConfigResponseDTO>> list() {
        try {
            List<ModelConfigResponseDTO> data = modelConfigService.list().stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
            return Response.<List<ModelConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            return error("查询模型配置失败", e);
        }
    }

    @PostMapping("save")
    public Response<ModelConfigResponseDTO> save(@RequestBody ModelConfigRequestDTO requestDTO) {
        try {
            ModelConfigEntity saved = modelConfigService.save(toEntity(requestDTO));
            dynamicAgentRegistry.evict(saved.getId());
            userModelChatModelFactory.evict(saved.getId());
            return Response.<ModelConfigResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toResponse(saved))
                    .build();
        } catch (ModelConfigException e) {
            return Response.<ModelConfigResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            return error("保存模型配置失败", e);
        }
    }

    @PostMapping("delete")
    public Response<Void> delete(@RequestParam("id") Long id) {
        try {
            modelConfigService.delete(id);
            dynamicAgentRegistry.evict(id);
            userModelChatModelFactory.evict(id);
            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (ModelConfigException e) {
            return Response.<Void>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            return error("删除模型配置失败", e);
        }
    }

    @PostMapping("reveal")
    public Response<String> revealApiKey(@RequestParam("id") Long id) {
        try {
            return Response.<String>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(modelConfigService.revealApiKey(id))
                    .build();
        } catch (ModelConfigException e) {
            return Response.<String>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            return error("查看模型密钥失败", e);
        }
    }

    @PostMapping("test")
    public Response<String> test(@RequestBody ModelConfigRequestDTO requestDTO) {
        try {
            String result = modelConfigService.test(toEntity(requestDTO));
            return Response.<String>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("连接成功")
                    .data(result)
                    .build();
        } catch (ModelConfigException e) {
            return Response.<String>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            return error("测试模型连接失败", e);
        }
    }

    private ModelConfigResponseDTO toResponse(ModelConfigEntity entity) {
        String apiKey = StringUtils.defaultString(entity.getApiKey());
        String masked = apiKey.length() <= 8 ? "****" : apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
        return ModelConfigResponseDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .baseUrl(entity.getBaseUrl())
                .modelName(entity.getModelName())
                .completionsPath(entity.getCompletionsPath())
                .hasApiKey(StringUtils.isNotBlank(apiKey))
                .apiKeyMasked(masked)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private ModelConfigEntity toEntity(ModelConfigRequestDTO requestDTO) {
        if (requestDTO == null) {
            throw new ModelConfigException("请求参数不能为空");
        }
        return ModelConfigEntity.builder()
                .id(requestDTO.getId())
                .name(StringUtils.trimToNull(requestDTO.getName()))
                .baseUrl(StringUtils.trimToNull(requestDTO.getBaseUrl()))
                .apiKey(StringUtils.trimToNull(requestDTO.getApiKey()))
                .modelName(StringUtils.trimToNull(requestDTO.getModelName()))
                .completionsPath(StringUtils.trimToNull(requestDTO.getCompletionsPath()))
                .build();
    }

    private <T> Response<T> error(String message, Exception e) {
        log.error(message, e);
        return Response.<T>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(message)
                .build();
    }
}
