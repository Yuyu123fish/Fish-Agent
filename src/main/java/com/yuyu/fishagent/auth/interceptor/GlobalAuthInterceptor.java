package com.yuyu.fishagent.auth.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.auth.context.UserContext;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.auth.session.RedisSessionManager;
import com.yuyu.fishagent.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 全局鉴权拦截器：校验 Redis 会话，写入 {@link UserContextHolder}，并刷新 TTL。
 * <p>白名单路径（{@link AuthProperties#getWhiteList()}）跳过校验。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GlobalAuthInterceptor implements HandlerInterceptor {

    /** 与前端约定的请求头名。 */
    public static final String HEADER_AUTH_TOKEN = "X-Auth-Token";

    private final AuthProperties authProperties;
    private final RedisSessionManager redisSessionManager;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // CORS 预检无需会话
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String uri = request.getRequestURI();
        List<String> white = authProperties.resolvedWhiteListPrefixes();
        for (String prefix : white) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }

        String token = resolveToken(request);
        Optional<UserContext> ctxOpt = redisSessionManager.getSession(token);
        if (ctxOpt.isEmpty()) {
            String reason;
            if (token == null || token.isBlank()) {
                reason = "缺少认证令牌";
            } else {
                String prefix = token.length() > 8 ? token.substring(0, 8) + "***" : token;
                reason = String.format("令牌无效或已过期 (prefix=%s)", prefix);
            }
            log.warn("[Auth] 401 未授权访问 — URI: {}, 原因: {}", request.getRequestURI(), reason);
            sendUnauthorized(response, "invalid or expired session");
            return false;
        }

        UserContextHolder.set(ctxOpt.get());
        redisSessionManager.refreshTtl(token);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContextHolder.clear();
    }

    /**
     * 从 Header 或 Query（SSE 等场景兜底）解析 token。
     */
    private static String resolveToken(HttpServletRequest request) {
        String h = request.getHeader(HEADER_AUTH_TOKEN);
        if (h != null && !h.isBlank()) {
            return h.trim();
        }
        String q = request.getParameter("token");
        return q == null ? "" : q.trim();
    }

    /**
     * 返回 JSON 401，便于前端统一处理。
     */
    private void sendUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "error", "UNAUTHORIZED",
                "message", message == null ? "" : message));
        response.getOutputStream().write(body);
    }
}
