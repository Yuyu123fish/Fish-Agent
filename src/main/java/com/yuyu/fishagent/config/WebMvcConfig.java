package com.yuyu.fishagent.config;

import com.yuyu.fishagent.auth.interceptor.GlobalAuthInterceptor;
import com.yuyu.fishagent.auth.interceptor.PermissionInterceptor;
import com.yuyu.fishagent.auth.interceptor.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 跨域与拦截器链：全局鉴权 → 对话限流 → 权限拦截。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final GlobalAuthInterceptor globalAuthInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final PermissionInterceptor permissionInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(globalAuthInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/chat/**");
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/api/**");
    }
}
