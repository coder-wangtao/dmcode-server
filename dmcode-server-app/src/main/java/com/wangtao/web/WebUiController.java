package com.wangtao.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Web 控制台引导接口
 *
 * <p>服务端自带的轻量 Web 页面（classpath:/static）运行时，若处于 local profile，
 * /api/** 需要携带 Bearer token（LocalTokenFilter）。本接口向<strong>同源页面</strong>
 * 下发运行时 token，页面 JS 自动完成引导，无需用户手动配置。
 *
 * <p>安全说明：本接口<strong>不声明 @CrossOrigin</strong>，跨源页面无法读取响应体；
 * dev profile（无 token 校验）返回空 token。
 *
 * @author walissh dev
 * 2026/10/7
 */
@Slf4j
@RestController
@RequestMapping("/webui")
public class WebUiController {

    /**
     * local profile 下的运行时 token（LocalRuntimeConfig 提供）；dev 等其他 profile 下为 null。
     */
    @Autowired(required = false)
    @Qualifier("localRuntimeToken")
    private String localRuntimeToken;

    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap() {
        if (localRuntimeToken == null || localRuntimeToken.isBlank()) {
            return Map.of("token", "", "authRequired", false);
        }
        return Map.of("token", localRuntimeToken, "authRequired", true);
    }
}
