package com.wangtao;

import com.wangtao.domain.agent.service.armory.matter.mcp.server.MyTestMcpService;
import com.wangtao.domain.agent.service.armory.matter.mcp.server.SshExecuteMcpService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;
import java.nio.file.Path;
@SpringBootApplication
@Configurable
public class Application {

    public static void main(String[] args) {
        prepareLocalDataDirectory(args);
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    private static void prepareLocalDataDirectory(String[] args) {
        String systemProfile = System.getProperty("spring.profiles.active", "");
        String environmentProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        boolean local = systemProfile.contains("local")
                || (environmentProfile != null && environmentProfile.contains("local"))
                || java.util.Arrays.stream(args).anyMatch(arg -> arg.contains("spring.profiles.active=local"));
        if (local) {
            try {
                Files.createDirectories(Path.of(System.getProperty("user.home"), ".dmcode", "data"));
            } catch (Exception e) {
                throw new IllegalStateException("无法创建本地数据目录", e);
            }
        }
    }

    /** 测试工具：大小写转换 */
    @Bean("myToolCallbackProvider")
    public ToolCallbackProvider testTools(MyTestMcpService toolService) {
        return MethodToolCallbackProvider.builder().toolObjects(toolService).build();
    }

    /** SSH 命令执行工具 */
    @Bean("sshToolCallbackProvider")
    public ToolCallbackProvider sshTools(SshExecuteMcpService sshService) {
        return MethodToolCallbackProvider.builder().toolObjects(sshService).build();
    }

}
