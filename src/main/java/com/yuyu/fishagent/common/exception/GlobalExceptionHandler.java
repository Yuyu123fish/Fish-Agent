package com.yuyu.fishagent.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 兜底异常处理：把未捕获的异常统一包装成 JSON 错误响应。
 * <p>SSE 接口的异常已由 {@code SseEmitter#completeWithError} 处理，不再在此重复转换。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验异常，返回 400 错误
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", 400,
                "message", e.getMessage() == null ? "bad request" : e.getMessage()
        ));
    }

    /**
     * 未认证上下文（如误调需要登录的存储接口）。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> illegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "code", 401,
                "message", e.getMessage() == null ? "unauthorized" : e.getMessage()
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException e) {
        int code = e.getStatusCode().value();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                "code", code,
                "message", e.getReason() == null ? "" : e.getReason()
        ));
    }

    /**
     * 同一会话流式对话并发：Redis 会话锁未获取到，返回 409 Conflict。
     */
    @ExceptionHandler(SessionLockedException.class)
    public ResponseEntity<Map<String, Object>> sessionLocked(SessionLockedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "SESSION_LOCKED",
                "message", e.getMessage() == null ? "" : e.getMessage()
        ));
    }

    /**
     * 处理所有未捕获的异常，返回 500 错误
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception e) {
        log.error("[GlobalExceptionHandler] 未捕获异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", 500,
                "message", e.getMessage() == null ? "internal error" : e.getMessage()
        ));
    }
}
