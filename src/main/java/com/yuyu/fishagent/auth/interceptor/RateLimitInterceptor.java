package com.yuyu.fishagent.auth.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.config.RateLimitProperties;
import com.yuyu.fishagent.ratelimit.RateLimitResult;
import com.yuyu.fishagent.ratelimit.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话接口限流：令牌桶（每用户）+ SSE 流并发槽位（仅 {@code POST /api/chat/stream}）。
 * <p>须注册在 {@link GlobalAuthInterceptor} 之后，以便 {@link UserContextHolder} 已有 userId。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String CODE_RATE_LIMIT = "RATE_LIMIT_EXCEEDED";
    public static final String CODE_CONCURRENT = "CONCURRENT_LIMIT_EXCEEDED";

    private static final String CHAT_STREAM_SUFFIX = "/api/chat/stream";

    private final RateLimitProperties rateLimitProperties;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!rateLimitProperties.isEnabled()) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        Long userId = UserContextHolder.currentUserIdOrNull();
        if (userId == null) {
            return true;
        }

        boolean sseStreamPost = isChatStreamPost(request);
        RateLimitResult result = rateLimitService.evaluate(userId, sseStreamPost);

        return switch (result) {
            case RateLimitResult.Allowed() -> true;
            case RateLimitResult.TokenBucketDenied(var retryAfter) -> {
                sendTooManyRequests(response, CODE_RATE_LIMIT,
                        "Too many requests", retryAfter);
                yield false;
            }
            case RateLimitResult.ConcurrentDenied() -> {
                sendTooManyRequests(response, CODE_CONCURRENT,
                        "Too many concurrent connections", null);
                yield false;
            }
        };
    }

    private static boolean isChatStreamPost(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri != null && (uri.endsWith(CHAT_STREAM_SUFFIX) || uri.endsWith(CHAT_STREAM_SUFFIX + "/"));
    }

    private void sendTooManyRequests(HttpServletResponse response, String code, String message,
            Integer retryAfterSeconds) throws Exception {
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        if (retryAfterSeconds != null) {
            body.put("retryAfter", retryAfterSeconds);
        }

        byte[] bytes = objectMapper.writeValueAsBytes(body);
        response.getOutputStream().write(bytes);
    }
}
