package com.sentryagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 开发用 CORS 配置（前后端分离：前端独立部署在 frontend/，默认 origin 为
 * file:// 或本地静态服务器，与后端 8084 不同源）。
 *
 * <p>只加这一个配置类，不碰任何业务代码。范围限 /api/**，演示期放开所有来源
 * （allowedOriginPatterns "*"，含 file:// 的 "null" origin）；生产按需收紧为白名单。
 * 不带 allowCredentials——接口无会话 Cookie，预检可被公共缓存优化。</p>
 *
 * <p>SSE 说明：GET /api/incidents/{id}/stream 若带自定义头会先发 OPTIONS 预检，
 * Spring MVC 的 CorsInterceptor 在 handler 之前直接应答预检（返回 200 且不进入
 * 控制器方法体），因此预检不会创建 SseEmitter 挂死连接；实际流式 GET 的响应头
 * 由同一个拦截器补 Access-Control-Allow-Origin，EventSource 跨域订阅即可用。</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
