package com.wangtao.config;

import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(-100)
@Profile("local")
//当项目运行在 local 环境时，所有 /api/** 业务接口都必须携带正确的 Bearer Token 才能访问；
// 而静态页面、前端资源和 /webui/** 接口不需要经过这个 Token 校验。
public class LocalTokenFilter extends OncePerRequestFilter {

    @Resource(name = "localRuntimeToken")
    private String runtimeToken;

    //这个方法决定当前请求是否跳过过滤器。
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 仅对 /api/** 业务接口做 Bearer token 校验；
        // 静态 Web 页面（index.html / css / js）与 /webui/** 引导接口放行，
        // 页面前端通过 /webui/bootstrap 获取 token 后自行携带。
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && constantTimeEquals(authorization, "Bearer " + runtimeToken)) {
            //认证成功后继续执行
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"A0301\",\"info\":\"local runtime token required\",\"data\":null}");
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }
}
