package com.wangtao.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源缓存策略。
 *
 * 前端迭代频繁（app.js/style.css 等按 ?v=Date.now() 破缓存），但 index.html 本身
 * 没有 no-cache 头时，浏览器会按启发式策略缓存旧页面 —— 旧页面引用旧 JS，导致
 * 新功能（如 mermaid 渲染）不生效。这里统一规定：
 * - index.html / 模板入口：no-cache，每次都向服务端校验（304 走 Last-Modified，开销极小）
 * - 其他静态资源：服务端不发长缓存头，交给页面的 ?v= 时间戳破缓存
 */
//配置 Spring Boot 项目中静态资源的 HTTP 缓存策略，重点是不让浏览器直接使用缓存的旧版 index.html，而是每次访问时向服务器重新验证页面是否更新。
@Configuration
public class WebStaticCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/", "/index.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }
}
