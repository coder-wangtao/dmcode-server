package com.wangtao.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Web 控制台启动提示：服务就绪后打印浏览器可访问的页面地址。
 *
 * <p>local profile 端口为动态分配（server.port=0），必须从 WebServer 读取实际端口；
 * 桌面版（Dmcode-client 内嵌启动）时同样生效，用户可按日志地址在浏览器打开 Web 版。
 *
 * @author wt
 */
@Slf4j
@Component
public class WebUiStartupLogger implements ApplicationListener<ApplicationReadyEvent> {

    private static final String BANNER = """
            ┌──────────────────────────────────────────────────────────────┐
            │  🖥  Dmcode Web 控制台已就绪，浏览器访问：{}  │
            └──────────────────────────────────────────────────────────────┘""";

    @Override
    // 这段代码是一个 Spring Boot 项目启动完成后的 Web 控制台地址打印器。
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            if (!(event.getApplicationContext() instanceof WebServerApplicationContext webContext)) {
                return;
            }
            int port = webContext.getWebServer().getPort();
            Environment env = event.getApplicationContext().getEnvironment();
            String address = env.getProperty("server.address", "");
            String host = (address.isBlank() || "0.0.0.0".equals(address)) ? "localhost" : address;

            String url = "http://" + host + ":" + port + "/";
            log.info(BANNER, url);
            log.info("  方式一（独立运行）：直接用浏览器打开上面的地址即可使用；");
            log.info("  方式二（桌面版内置）：本服务由 dmcode-client 自动拉起，端口为动态分配，");
            log.info("        同样可用浏览器打开上面的地址访问 Web 版（token 已自动引导，无需配置）。");
        } catch (Exception e) {
            log.warn("Web 控制台地址打印失败: {}", e.getMessage());
        }
    }
}
