package com.wangtao.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.security.SecureRandom;
import java.util.HexFormat;

@Slf4j
@Configuration
@Profile("local")
public class LocalRuntimeConfig {

    @Value("${dmcode.local.token:}")
    private String configuredToken;

    @Bean("localRuntimeToken")
    public String localRuntimeToken() {
        if (configuredToken == null || configuredToken.isBlank()) {
            byte[] token = new byte[32];
            new SecureRandom().nextBytes(token);
            return HexFormat.of().formatHex(token);
        }
        return configuredToken;
    }

    @Bean
    public WebMvcConfigurer localCorsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*", "tauri://localhost")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders("Authorization")
                        .allowCredentials(false);
            }
        };
    }

    //监听 Spring Boot 启动完成事件
    //当 Spring Boot 后端完全启动成功后，获取后端运行信息，并通过标准输出（stdout）向外部程序发送一条 JSON 消息。
    //那么这段代码实际上就是 Java 后端向 Tauri 发送的一个 启动成功通知（Ready Handshake）。
    @EventListener(ApplicationReadyEvent.class)
    public void publishReadyEvent(ApplicationReadyEvent event) {
        String port = System.getProperty("local.server.port", "0");
        if ("0".equals(port) && event.getApplicationContext() instanceof WebServerApplicationContext webContext) {
            port = String.valueOf(webContext.getWebServer().getPort());
        }
        //获取当前JAVA进程 PID
        String pid = String.valueOf(ProcessHandle.current().pid());
        String token = localRuntimeToken();
        String payload = "{\"event\":\"ready\",\"port\":" + port
                + ",\"pid\":" + pid
                + ",\"version\":\"1.0\",\"token\":\"" + token + "\"}";
        System.out.println(payload);
        System.out.flush();
    }
}
