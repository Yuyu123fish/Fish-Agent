package com.yuyu.fishagent.chat;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.chat.dto.ChatRequest;
import com.yuyu.fishagent.chat.dto.SessionInfo;
import com.yuyu.fishagent.common.trace.TraceConstants;
import com.yuyu.fishagent.common.exception.SessionLockedException;
import com.yuyu.fishagent.chat.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 聊天 REST 接口。
 * <ul>
 *   <li>{@code POST /api/chat/stream}：流式对话（SSE）；</li>
 *   <li>{@code GET /api/chat/sessions}：列出全部会话摘要；</li>
 *   <li>{@code GET /api/chat/sessions/{sid}}：取某会话历史；</li>
 *   <li>{@code DELETE /api/chat/sessions/{sid}}：删除会话；</li>
 *   <li>{@code PATCH /api/chat/sessions/{sid}}：重命名会话标题。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    /** SSE 流式响应的超时时间（毫秒），覆盖默认的 30s。 */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final ChatService chatService;

    /**
     * 流式对话接口
     * 通过 SSE 推送 AI 响应的 token chunk
     *
     * @param req 包含会话ID和用户消息的请求对象
     * @return SSE 发射器
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        try {
            String traceId = MDC.get(TraceConstants.TRACE_ID);
            if (traceId != null && !traceId.isBlank()) {
                // 首个 SSE 事件返回 traceId，前端可在报错上报时一并携带，方便后端按链路排查。
                emitter.send(SseEmitter.event().name(TraceConstants.SSE_EVENT_TRACE).data(traceId));
            }
            chatService.streamChat(req.getSessionId(), req.getMessage(), emitter);
        } catch (SessionLockedException e) {
            // 须交由 GlobalExceptionHandler 转为 HTTP 409，勿走 SseEmitter.completeWithError
            throw e;
        } catch (Exception e) {
            log.error("[ChatController] /stream 启动失败", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * 获取所有会话摘要列表
     */
    @GetMapping("/sessions")
    public List<SessionInfo> sessions() {
        return chatService.listSessions();
    }

    /**
     * 获取指定会话的完整历史记录
     */
    @GetMapping("/sessions/{sessionId}")
    public List<ChatMessageDTO> history(@PathVariable String sessionId) {
        return chatService.getHistory(sessionId);
    }

    /**
     * 删除指定会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public void deleteSession(@PathVariable String sessionId) {
        chatService.deleteSession(sessionId);
    }

    /**
     * 重命名会话标题
     */
    @PatchMapping("/sessions/{sessionId}")
    public void renameSession(@PathVariable String sessionId,
                              @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be empty");
        }
        chatService.renameTitle(sessionId, title);
    }
}
