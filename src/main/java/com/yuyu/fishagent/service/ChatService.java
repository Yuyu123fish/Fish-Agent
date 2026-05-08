package com.yuyu.fishagent.service;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.yuyu.fishagent.agent.ChatAgent;
import com.yuyu.fishagent.auth.context.UserContext;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.agent.memory.history.ChatMemoryStore;
import com.yuyu.fishagent.agent.memory.shortterm.ShortTermMemorySnapshot;
import com.yuyu.fishagent.agent.memory.shortterm.ShortTermMemoryStore;
import com.yuyu.fishagent.agent.memory.rag.recall.RagRecall;
import com.yuyu.fishagent.config.AgentProperties;
import com.yuyu.fishagent.config.MemoryProperties;
import com.yuyu.fishagent.ratelimit.RateLimitService;
import com.yuyu.fishagent.dto.ChatMessageDTO;
import com.yuyu.fishagent.dto.MemoryCompressionRequest;
import com.yuyu.fishagent.dto.SessionInfo;
import com.yuyu.fishagent.exception.SessionLockedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天编排服务：负责
 * <ol>
 *   <li>会话历史的加载与持久化（{@link ChatMemoryStore}）；</li>
 *   <li>把历史 + 当前用户输入组装为 {@link Message} 列表；</li>
 *   <li>订阅 {@link ChatAgent#stream} 并把 token chunk 通过 SSE 推送给前端；</li>
 *   <li>结束后把完整的 user 与 assistant 消息追加落盘。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /**
     * 会话时钟格式化器，与 {@link com.yuyu.fishagent.agent.tool.builtin.DateTimeToolProvider}
     * 默认分支（不传 timezone）保持一致，便于模型对齐工具返回值。
     */
    private static final DateTimeFormatter SESSION_CLOCK_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** ReAct 模式的助手 Agent，负责「思考-行动-观察」循环，产出流式 token。 */
    private final ChatAgent chatAgent;
    /** 会话历史的持久化存储（文件系统），用于完整历史的加载与落盘。 */
    private final ChatMemoryStore memoryStore;
    /** Redis 短期记忆存储，存放对话摘要和最近 N 条窗口消息。 */
    private final ShortTermMemoryStore shortTermMemoryStore;
    /** 记忆压缩服务，当对话轮次超过阈值时异步生成短期摘要写入 Redis。 */
    private final MemoryCompressionService memoryCompressionService;
    /** 长期记忆主动录入服务，每轮用户输入后异步判断是否将事实写入 ES。 */
    private final LongTermMemoryIngestionService longTermMemoryIngestionService;
    /**
     * 长期记忆 RAG 上下文服务，由 {@link RagRecall} 编排。
     * 负责基于当前用户输入检索 ES 中的长期记忆，生成可注入模型的增强片段。
     * 查询重写与子查询扩展分别在 {@code agent.memory.rag.query}、{@code agent.memory.rag.expand} 包下实现。
     */
    private final RagRecall.Augmentation longTermRagContextService;
    /** Agent 全局配置（人设指令、最大迭代次数等）。 */
    private final AgentProperties properties;
    /** 记忆相关配置（短期窗口大小、摘要触发阈值等）。 */
    private final MemoryProperties memoryProperties;
    /** SSE 并发计数递减（与 {@link com.yuyu.fishagent.auth.interceptor.RateLimitInterceptor} 的 INCR 对称）。 */
    private final RateLimitService rateLimitService;
    /** 会话元数据（MySQL）：侧栏标题等。 */
    private final ChatMetadataService chatMetadataService;

    /**
     * 列出所有已持久化的会话。
     *
     * @return 所有会话的基础信息列表（ID、最后更新时间等）
     */
    public List<SessionInfo> listSessions() {
        return memoryStore.listSessions();
    }

    /**
     * 加载指定会话的完整历史消息。
     *
     * @param sessionId 会话 ID
     * @return 该会话的所有消息记录（按时间顺序）
     */
    public List<ChatMessageDTO> getHistory(String sessionId) {
        return memoryStore.load(sessionId);
    }

    /**
     * 删除指定会话的全部历史记录。
     *
     * @param sessionId 会话 ID
     */
    public void deleteSession(String sessionId) {
        memoryStore.clear(sessionId);
    }

    /**
     * 重命名会话标题（元数据层）。
     *
     * @param sessionId 会话 ID
     * @param newTitle  新标题
     */
    public void renameTitle(String sessionId, String newTitle) {
        chatMetadataService.renameTitle(sessionId, newTitle);
    }

    /**
     * 启动一次流式推理，通过 SSE 向前端推送结果。
     *
     * <p>核心流程：</p>
     * <ol>
     *   <li>解析或生成 sessionId，第一时间通过 {@code session} 事件通知前端；</li>
     *   <li>加载历史、组装上下文（短期摘要 + RAG + 滑动窗口），订阅 {@link ChatAgent#stream}；</li>
     *   <li>逐 token 向前端推送 {@code chunk} 事件，同时过滤段聚合和重复 chunk；</li>
     *   <li>流完成时推送 {@code done} 事件、持久化本轮消息、触发记忆压缩和长期记忆录入；</li>
     *   <li>任何异常均通过 {@code error} 事件反映给前端。</li>
     * </ol>
     *
     * @param sessionId 会话 ID（为 {@code null} 或空白时自动生成新 UUID）
     * @param userInput 用户当前输入
     * @param emitter   {@link SseEmitter} 实例（由 Controller 创建并已设置 timeout）
     * @return 实际使用的 sessionId（前端需要用它做后续轮次的标识）
     */
    public String streamChat(String sessionId, String userInput, SseEmitter emitter) {
        final String sid = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString() : sessionId;

        // ReAct 完成回调可能在非 Servlet 线程执行，ThreadLocal 不会自动传递，此处快照用户上下文供 persist 使用。
        final UserContext streamUserSnapshot = UserContextHolder.get();
        final Long streamUserId = streamUserSnapshot == null ? null : streamUserSnapshot.userId();

        // 须先于会话锁检查创建：拦截器已对 SSE 并发 INCR，若抢锁失败须在此 Runnable 中 DECR，否则槽位泄漏。
        AtomicBoolean sseSlotReleased = new AtomicBoolean(false);
        Runnable releaseSseSlotOnce = () -> {
            if (sseSlotReleased.compareAndSet(false, true)) {
                if (streamUserId != null) {
                    rateLimitService.decrementSseConcurrent(streamUserId);
                }
                rateLimitService.releaseSessionLock(streamUserId, sid);
            }
        };

        if (!rateLimitService.tryAcquireSessionLock(streamUserId, sid)) {
            releaseSseSlotOnce.run();
            throw new SessionLockedException("此会话正在处理中，请等待回复完成后再发送");
        }

        if (userInput == null || userInput.isBlank()) {
            safeError(emitter, new IllegalArgumentException("message cannot be empty"));
            releaseSseSlotOnce.run();
            return sid;
        }

        try {
            emitter.send(SseEmitter.event().name("session").data(sid));
        } catch (IOException ignore) {
            // ignore
        }

        final List<ChatMessageDTO> historyDtos;
        final List<Message> messages;
        try {
            // 1. 组装上下文：文件历史仍是事实来源，模型上下文使用“短期摘要 + 滑动窗口”控制长度。
            historyDtos = memoryStore.load(sid);
            messages = buildMessages(sid, historyDtos, userInput);
            messages.add(new UserMessage(userInput));
        } catch (Exception e) {
            log.error("[ChatService] 组装上下文失败 sid={}", sid, e);
            releaseSseSlotOnce.run();
            safeError(emitter, e);
            return sid;
        }

        // 2. 注册 emitter 生命周期回调（必须在 subscribe 之前：若 onComplete 异步触发并调用
        //    emitter.complete() 时回调尚未注册，releaseSseSlotOnce 将永远不被执行，导致会话锁泄漏）。
        final Disposable[] disposableRef = new Disposable[1];
        emitter.onTimeout(() -> {
            log.warn("[ChatService] SSE 超时, sid={}", sid);
            releaseSseSlotOnce.run();
            if (disposableRef[0] != null) disposableRef[0].dispose();
        });
        emitter.onCompletion(() -> {
            releaseSseSlotOnce.run();
            if (disposableRef[0] != null) disposableRef[0].dispose();
        });
        emitter.onError(e -> {
            releaseSseSlotOnce.run();
            if (disposableRef[0] != null) disposableRef[0].dispose();
        });

        // 3. 订阅流式输出
        AssistantBuf assistantBuf = new AssistantBuf();
        //调用大模型流式推理
        disposableRef[0] = chatAgent.stream(messages, sid).subscribe(
                node -> handleNode(node, emitter, assistantBuf),
                err -> {
                    log.warn("[ChatService] 流式异常 sid={}: {}", sid, err.getMessage());
                    safeError(emitter, err);
                },
                () -> {
                    String full = assistantBuf.full.toString().trim();
                    if (streamUserSnapshot != null) {
                        UserContextHolder.set(streamUserSnapshot);
                    }
                    try {
                        try {
                            persist(sid, userInput, full);
                        } catch (Exception e) {
                            log.error("[ChatService] 持久化失败 sid={}: {}", sid, e.getMessage(), e);
                            // persist 失败不阻塞 emitter 关闭
                        }
                        safeSend(emitter, "done", full);
                        emitter.complete();
                        triggerLongTermMemoryIngestion(streamUserId, sid, userInput);
                        triggerMemoryCompressionIfNeeded(sid, historyDtos, userInput, full);
                    } finally {
                        UserContextHolder.clear();
                    }
                }
        );

        return sid;
    }

    /* ---------------- private helpers ---------------- */

    /**
     * 构造模型上下文。短期记忆存在时优先使用 Redis 中的摘要和窗口；否则从文件历史截取最近 N 条。
     * <p>每条请求在合并系统段<strong>最前</strong>注入服务器当前时间（JVM 默认时区），与 {@code get_current_datetime} 不传参时一致。</p>
     * <p>RAG 长期记忆片段插在短期摘要之后、滑动窗口之前，便于模型先看到压缩摘要再看到可引用事实，最后进入多轮语气上下文。
     * 用户消息仍以原始 {@code userInput} 入模（见 streamChat），避免历史文件与前端展示被改写。</p>
     */
    private List<Message> buildMessages(String sid, List<ChatMessageDTO> historyDtos, String userInput) {
        List<Message> messages = new ArrayList<>();
        // 合并为单条 SystemMessage：Alibaba ReactAgent 还会在内部拼接系统位，多条 SystemMessage 会触发 AgentLlmNode 英文 WARN 且不利于模型解析。
        StringBuilder systemBlock = new StringBuilder();
        systemBlock.append(sessionClockAnchorLine());

        String instruction = properties.getInstruction() == null ? "" : properties.getInstruction().trim();
        if (!instruction.isBlank()) {
            systemBlock.append("\n\n---\n");
            systemBlock.append(instruction);
        }

        ShortTermMemorySnapshot snapshot = shortTermMemoryStore.load(sid);
        if (snapshot.summary() != null && !snapshot.summary().isBlank()) {
            if (!systemBlock.isEmpty()) {
                systemBlock.append("\n\n---\n");
            }
            systemBlock.append("以下是此前对话的短期记忆摘要，请作为上下文参考：\n").append(snapshot.summary().trim());
            log.debug("[ChatService] 使用短期记忆摘要 sid={}, summaryLen={}", sid, snapshot.summary().length());
        }

        Optional<String> rag = longTermRagContextService.buildAugmentation(sid, userInput);
        if (rag.isPresent() && !rag.get().isBlank()) {
            if (!systemBlock.isEmpty()) {
                systemBlock.append("\n\n---\n");
            }
            systemBlock.append(rag.get().trim());
            log.debug("[ChatService] 已注入长期记忆 RAG sid={}, blockLen={}", sid, rag.get().length());
        }

        messages.add(new SystemMessage(systemBlock.toString()));

        // 多轮语气：优先用 Redis 中的「最近窗口」；否则从落盘全量历史尾部截取
        List<ChatMessageDTO> contextMessages = snapshot.recentMessages() == null || snapshot.recentMessages().isEmpty()
                ? recentMessages(historyDtos, memoryProperties.getShortTermWindowSize())
                : snapshot.recentMessages();
        log.debug("[ChatService] 组装模型上下文 sid={}, historySize={}, contextWindowSize={}",
                sid, historyDtos.size(), contextMessages.size());
        appendReplayableMessages(messages, contextMessages);
        return messages;
    }

    /**
     * 生成注入 SystemMessage 的「当前会话时间」锚点行。
     * <p>
     * 使用 JVM 默认时区（{@link ZoneId#systemDefault()}），格式为 {@code yyyy-MM-dd HH:mm:ss}，
     * 与 {@link com.yuyu.fishagent.agent.tool.builtin.DateTimeToolProvider} 不传 timezone 参数时的返回值一致。
     * 这样模型无论通过工具获取时间还是从 SystemMessage 中读取，都能对齐同一时区基准。
     * </p>
     *
     * @return 例如：{@code "当前会话时间（服务器，时区 Asia/Shanghai）：2026-05-04 14:30:00。"}
     */
    private static String sessionClockAnchorLine() {
        ZoneId zone = ZoneId.systemDefault();
        String dt = LocalDateTime.now(zone).format(SESSION_CLOCK_FORMAT);
        return "当前会话时间（服务器，时区 " + zone.getId() + "）：" + dt + "。";
    }

    /**
     * 将历史消息（DTO 形式）转换为 Spring AI 的 {@link Message} 并追加到上下文列表。
     * <p>
     * 仅回放 {@code user} 和 {@code assistant} 角色的消息；
     * {@code tool} / {@code system} 类型暂不回放，避免污染模型上下文或导致框架告警。
     * </p>
     *
     * @param messages    模型上下文消息列表（被原地修改）
     * @param historyDtos 已持久化的历史消息
     */
    private void appendReplayableMessages(List<Message> messages, List<ChatMessageDTO> historyDtos) {
        for (ChatMessageDTO m : historyDtos) {
            if (m.getRole() == null) {
                continue;
            }
            switch (m.getRole().toLowerCase()) {
                case "user" -> messages.add(new UserMessage(m.getContent()));
                case "assistant" -> messages.add(new AssistantMessage(m.getContent()));
                default -> { /* tool/system 类型暂不回放，避免污染上下文 */ }
            }
        }
    }

    /**
     * 从全量历史中截取最近 {@code windowSize} 条消息，作为模型的多轮上下文窗口。
     * <p>
     * 当 Redis 中不存在短期记忆窗口时作为降级策略使用。
     * 若历史不足 {@code windowSize} 条，则返回全部。
     * </p>
     *
     * @param chatHistory 全量历史消息列表（按时间顺序）
     * @param windowSize  滑动窗口大小（配置项 {@code memory.short-term-window-size}）
     * @return 最近 N 条消息的副本
     */
    private List<ChatMessageDTO> recentMessages(List<ChatMessageDTO> chatHistory, int windowSize) {
        if (chatHistory == null || chatHistory.isEmpty() || windowSize <= 0) {
            return List.of();
        }
        int fromIndex = Math.max(0, chatHistory.size() - windowSize);
        return new ArrayList<>(chatHistory.subList(fromIndex, chatHistory.size()));
    }

    /**
     * 当累积的对话轮次超过配置阈值时，异步触发短期记忆压缩。
     * <p>
     * 压缩会再次调用 LLM 模型，因此放在 {@link CompletableFuture#runAsync} 中执行，
     * 避免阻塞主流程和 SSE {@code done} 事件返回。
     * <br>
     * 触发条件：已落盘历史 + 本轮 user/assistant 消息总数 ≥ {@code memory.summary-trigger-threshold}。
     * </p>
     *
     * @param sid         会话 ID
     * @param historyDtos 本轮之前已落盘的历史消息
     * @param userInput   本轮用户输入
     * @param assistant   本轮助手完整回复（可能为空白）
     */
    private void triggerMemoryCompressionIfNeeded(String sid, List<ChatMessageDTO> historyDtos, String userInput, String assistant) {
        List<ChatMessageDTO> fullHistory = new ArrayList<>(historyDtos.size() + 2);
        fullHistory.addAll(historyDtos);
        fullHistory.add(ChatMessageDTO.of("user", userInput));
        if (assistant != null && !assistant.isBlank()) {
            fullHistory.add(ChatMessageDTO.of("assistant", assistant));
        }
        if (fullHistory.size() < memoryProperties.getSummaryTriggerThreshold()) {
            log.debug("[ChatService] 未触发记忆压缩 sid={}, historySize={}, threshold={}",
                    sid, fullHistory.size(), memoryProperties.getSummaryTriggerThreshold());
            return;
        }

        // 压缩会再次调用模型，放到异步任务中，避免拖慢 SSE done 事件返回。
        log.debug("[ChatService] 触发异步记忆压缩 sid={}, historySize={}, threshold={}",
                sid, fullHistory.size(), memoryProperties.getSummaryTriggerThreshold());
        CompletableFuture.runAsync(() -> {
            try {
                memoryCompressionService.compress(new MemoryCompressionRequest(sid, fullHistory));
            } catch (Exception e) {
                log.warn("[ChatService] 记忆压缩失败 sid={}: {}", sid, e.getMessage());
            }
        });
    }

    /**
     * 每轮用户输入后异步判断是否需要录入长期记忆。
     * <p>
     * 不依赖短期摘要压缩阈值，每轮都主动触发。
     * 由 {@link LongTermMemoryIngestionService} 内部通过 LLM 判断用户输入中是否包含值得长期保留的事实信息，
     * 如果有则写入 Elasticsearch，供后续 RAG 检索。
     * </p>
     *
     * @param sid       会话 ID
     * @param userInput 用户当前输入
     */
    private void triggerLongTermMemoryIngestion(Long userId, String sid, String userInput) {
        log.debug("[ChatService] 触发异步长期记忆主动录入 uid={}, sid={}, inputLen={}",
                userId, sid, userInput == null ? 0 : userInput.length());
        // 捕获 userId：异步线程中 ThreadLocal 不可用
        CompletableFuture.runAsync(() -> longTermMemoryIngestionService.ingestFromUserInput(userId, sid, userInput));
    }

    /**
     * 处理 ReactAgent 输出的节点：仅向前端发送增量 token chunk，识别并跳过段聚合 chunk。
     *
     * <p>ReactAgent 在每段 LLM 调用（包括工具调用前后的多段）结束时，会再发一条
     * "该段聚合后的整文" 作为 {@link StreamingOutput} 推送一次。如果不识别就会让前端
     * 看到例如两份"长春市当前天气..."的重复输出。
     *
     * <p>判定规则：</p>
     * <ul>
     *   <li>当前 chunk 与<strong>本段</strong>已累积（{@code buf.full.substring(buf.segStart)}）在规范化后相同 → 段聚合，跳过并推进 {@code segStart}；</li>
     *   <li>再推一整块与<strong>已累计全文</strong>相同（规范化后）→ 整段重复，跳过；</li>
     *   <li>再推一整块与<strong>已累计全文末尾</strong>相同的长文本（常见：前半正常、后半整块再来一遍）→ 末尾重复，跳过。</li>
     * </ul>
     */
    private void handleNode(NodeOutput node, SseEmitter emitter, AssistantBuf buf) {
        if (!(node instanceof StreamingOutput so)) {
            log.debug("[ChatService] node: {}", node.getClass().getSimpleName());
            return;
        }
        String chunk = so.chunk();
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        String segText = buf.full.substring(buf.segStart);
        if (!segText.isEmpty() && streamingTextEquals(chunk, segText)) {
            buf.segStart = buf.full.length();
            log.debug("[ChatService] 跳过段聚合 chunk(len={})", chunk.length());
            return;
        }
        String normFull = normalizeStreamingText(buf.full.toString());
        String normChunk = normalizeStreamingText(chunk);
        // 整篇再推一次（与已累计全文相同）
        if (!normFull.isEmpty()
                && normChunk.length() >= DUPLICATE_FULL_MIN_CHARS
                && normChunk.contentEquals(normFull)) {
            log.debug("[ChatService] 跳过与已累计全文相同的重复 chunk(len={})", chunk.length());
            return;
        }
        // 仅后半段再推一次：已累计内容已以该 chunk 结尾（且 chunk 短于全文），典型为「介绍 + 长段」后长段整块重复
        if (normChunk.length() >= SUFFIX_DEDUP_MIN_CHARS
                && normFull.length() > normChunk.length()
                && normFull.endsWith(normChunk)) {
            log.debug("[ChatService] 跳过与已累计末尾重复的长 chunk(len={})", chunk.length());
            return;
        }
        buf.full.append(chunk);
        safeSend(emitter, "chunk", chunk);
    }

    /** 与「整段重复」判定配套：过短则不按全文去重，避免误伤模型故意重复的短句。 */
    private static final int DUPLICATE_FULL_MIN_CHARS = 48;

    /**
     * 「末尾整块重复」最小长度：略大以降低误伤（例如用户要求连续复述同一句短话）；
     * 仍能覆盖常见的大段二次推送（多一次模型回合或框架重复 flush）。
     */
    private static final int SUFFIX_DEDUP_MIN_CHARS = 160;

    /** 统一换行，减轻段聚合 chunk 与流式片段在 \\r\\n 上不一致导致的漏判。 */
    private static String normalizeStreamingText(String s) {
        return s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 比较两段流式文本是否相等（先统一换行符再比较）。
     * <p>
     * 用于去重判断：段聚合 chunk 与流式累积文本中可能存在 {@code \r\n} 与 {@code \n} 不一致，
     * 直接比较会导致漏判，因此需要先规范化。
     * </p>
     *
     * @param a 第一段文本
     * @param b 第二段文本
     * @return 规范化后是否内容相等
     */
    private static boolean streamingTextEquals(String a, String b) {
        return Objects.equals(normalizeStreamingText(a), normalizeStreamingText(b));
    }

    /**
     * 助手输出累积缓冲。
     * <ul>
     *   <li>{@code full}：所有真正应该展示给前端 / 落盘的完整文本（已剔除聚合重复）。</li>
     *   <li>{@code segStart}：当前 LLM 段在 {@code full} 中的起点；每识别到一次段聚合 chunk 后推进。</li>
     * </ul>
     */
    private static final class AssistantBuf {
        final StringBuilder full = new StringBuilder();
        int segStart = 0;
    }

    /**
     * 持久化本轮对话到文件系统历史存储。
     * <p>
     * 追加一条 {@code user} 消息和一条 {@code assistant} 消息（如果回复非空）。
     * 文件历史作为事实来源，即使后续压缩生成摘要，原始记录仍然保留。
     * </p>
     *
     * @param sid       会话 ID
     * @param userInput 用户输入
     * @param assistant 助手完整回复（可能为空字符串，例如工具调用后无文本输出）
     */
    private void persist(String sid, String userInput, String assistant) {
        List<ChatMessageDTO> toAppend = new ArrayList<>(2);
        toAppend.add(ChatMessageDTO.of("user", userInput));
        if (assistant != null && !assistant.isBlank()) {
            toAppend.add(ChatMessageDTO.of("assistant", assistant));
        }
        memoryStore.appendAll(sid, toAppend);
    }

    /**
     * 安全地发送 SSE 事件，吞掉 {@link IOException}。
     * <p>
     * 当客户端提前断开连接时，{@link SseEmitter#send} 会抛出 IOException，
     * 这是预期行为，不应传播为主流程异常，仅记录 debug 日志即可。
     * </p>
     *
     * @param emitter SSE 发射器
     * @param event   事件名称（如 {@code "chunk"}、{@code "done"}、{@code "session"}）
     * @param data    事件数据
     */
    private void safeSend(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            log.debug("[ChatService] SSE send 失败({}): {}", event, e.getMessage());
        }
    }

    /**
     * 安全地通过 SSE 向前端报告错误并终止流。
     * <p>
     * 先尝试发送 {@code error} 事件携带异常信息，然后调用 {@link SseEmitter#completeWithError} 结束 SSE 连接。
     * 若发送时客户端已断开（IOException），则直接完成错误终止，不重复处理。
     * </p>
     *
     * @param emitter SSE 发射器
     * @param err     异常对象
     */
    private void safeError(SseEmitter emitter, Throwable err) {
        try {
            emitter.send(SseEmitter.event().name("error").data(err.getMessage() == null ? "unknown error" : err.getMessage()));
        } catch (IOException ignore) {
            // ignore
        }
        emitter.completeWithError(err);
    }
}
