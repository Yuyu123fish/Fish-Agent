package com.yuyu.fishagent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.yuyu.fishagent.agent.ChatAgent;
import com.yuyu.fishagent.auth.context.UserContext;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.chat.budget.BudgetPlan;
import com.yuyu.fishagent.chat.budget.BudgetRequest;
import com.yuyu.fishagent.chat.budget.ContextBudgetAllocator;
import com.yuyu.fishagent.chat.budget.ContextWindowTrimmer;
import com.yuyu.fishagent.common.trace.MdcAsync;
import com.yuyu.fishagent.chat.history.ChatMemoryStore;
import com.yuyu.fishagent.common.util.TokenEstimator;
import com.yuyu.fishagent.llm.config.FishLlmProperties;
import com.yuyu.fishagent.memory.shortterm.ShortTermMemorySnapshot;
import com.yuyu.fishagent.memory.shortterm.ShortTermMemoryService;
import com.yuyu.fishagent.memory.shortterm.StructuredSummary;
import com.yuyu.fishagent.memory.shortterm.TopicSegment;
import com.yuyu.fishagent.memory.shortterm.UserSignals;
import com.yuyu.fishagent.memory.shortterm.KeyExcerpt;
import com.yuyu.fishagent.memory.agentstate.ActiveTask;
import com.yuyu.fishagent.memory.agentstate.AgentStateStore;
import com.yuyu.fishagent.memory.agentstate.AgentStateUpdater;
import com.yuyu.fishagent.memory.agentstate.SessionAgentState;
import com.yuyu.fishagent.memory.LongTermMemoryIngestionService;
import com.yuyu.fishagent.memory.MemoryCompressionService;
import com.yuyu.fishagent.memory.compress.MemoryResponseParser;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import com.yuyu.fishagent.agent.config.AgentProperties;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.common.ratelimit.RateLimitService;
import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.chat.dto.SessionInfo;
import com.yuyu.fishagent.common.exception.SessionLockedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
    /** 短期记忆三级协调器，负责 L1/L2/L3 的读穿和写穿。 */
    private final ShortTermMemoryService shortTermMemoryService;
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
    /** Agent 过程状态存储，与短期内容记忆解耦。 */
    private final AgentStateStore agentStateStore;
    /** Agent 状态规则更新器，负责工具调用记录和 LLM 推断状态合并。 */
    private final AgentStateUpdater agentStateUpdater;
    /** 用于把结构化压缩输出中的 agent_state 节点转为强类型状态。 */
    private final ObjectMapper objectMapper;
    /** 对话模型上下文窗口和预算相关配置。 */
    private final FishLlmProperties fishLlmProperties;
    /** 用于解析当前 provider 对应的模型名，匹配模型窗口覆盖配置。 */
    private final Environment environment;

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
        Long uid = UserContextHolder.currentUserIdOrNull();
        memoryStore.clear(sessionId);
        shortTermMemoryService.clear(uid, sessionId);
        agentStateStore.clear(uid, sessionId);
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
        // SSE 返回后 Filter 会清理 Servlet 线程 MDC，流式完成回调需显式复用入口处的 MDC 快照。
        final Map<String, String> streamMdcSnapshot = MDC.getCopyOfContextMap();

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

        final BuildMessagesResult buildResult;
        try {
            // 1. 组装上下文：热路径仅读 L1(Redis)；L1/L2 均未命中才由协调器回源 L3 全量历史。
            buildResult = buildMessages(sid, streamUserId, userInput);
            buildResult.messages().add(new UserMessage(userInput));
        } catch (Exception e) {
            log.error("[ChatService] 组装上下文失败 sid={}", sid, e);
            releaseSseSlotOnce.run();
            safeError(emitter, e);
            return sid;
        }

        // 2. 注册 emitter 生命周期回调（必须在 subscribe 之前：若 onComplete 异步触发并调用
        //    emitter.complete() 时回调尚未注册，releaseSseSlotOnce 将永远不被执行，导致会话锁泄漏）。
        final Disposable[] disposableRef = new Disposable[1];
        // SSE 生命周期回调运行在非 Servlet 线程，需从入口快照恢复 MDC 以保证日志携带 traceId。
        emitter.onTimeout(() -> {
            if (streamMdcSnapshot != null) MDC.setContextMap(streamMdcSnapshot);
            try {
                log.warn("[ChatService] SSE 超时, sid={}", sid);
            } finally { MDC.clear(); }
            releaseSseSlotOnce.run();
            if (disposableRef[0] != null) disposableRef[0].dispose();
        });
        emitter.onCompletion(() -> {
            if (streamMdcSnapshot != null) MDC.setContextMap(streamMdcSnapshot);
            try {
                log.debug("[ChatService] SSE 完成, sid={}", sid);
            } finally { MDC.clear(); }
            releaseSseSlotOnce.run();
            if (disposableRef[0] != null) disposableRef[0].dispose();
        });
        emitter.onError(e -> {
            if (streamMdcSnapshot != null) MDC.setContextMap(streamMdcSnapshot);
            try {
                log.warn("[ChatService] SSE 连接异常, sid={}", sid);
            } finally { MDC.clear(); }
            releaseSseSlotOnce.run();
            if (disposableRef[0] != null) disposableRef[0].dispose();
        });

        // 3. 订阅流式输出
        AssistantBuf assistantBuf = new AssistantBuf();
        //调用大模型流式推理
        disposableRef[0] = chatAgent.stream(buildResult.messages(), sid).subscribe(
                node -> handleNode(node, emitter, assistantBuf),
                err -> {
                    // Reactor 回调线程无 MDC，从入口快照恢复以使日志携带 traceId。
                    if (streamMdcSnapshot != null) MDC.setContextMap(streamMdcSnapshot);
                    try {
                        log.warn("[ChatService] 流式异常 sid={}: {}", sid, err.getMessage());
                    } finally { MDC.clear(); }
                    safeError(emitter, err);
                },
                () -> {
                    String full = assistantBuf.full.toString().trim();
                    if (streamUserSnapshot != null) {
                        UserContextHolder.set(streamUserSnapshot);
                    }
                    // Reactor 回调线程无 MDC，从入口快照恢复，使 persist / safeSend 日志携带 traceId。
                    if (streamMdcSnapshot != null) MDC.setContextMap(streamMdcSnapshot);
                    try {
                        ChatMessageDTO userMsg = ChatMessageDTO.of("user", userInput);
                        ChatMessageDTO assistantMsg = full.isBlank() ? null : ChatMessageDTO.of("assistant", full);
                        try {
                            persist(sid, userInput, full);
                            shortTermMemoryService.appendTurnToL1(sid, userMsg, assistantMsg);
                            updateAgentStateByRules(streamUserId, sid, assistantBuf.nodes);
                        } catch (Exception e) {
                            log.error("[ChatService] 持久化失败 sid={}: {}", sid, e.getMessage(), e);
                            // persist 失败不阻塞 emitter 关闭
                        }
                        safeSend(emitter, "done", full);
                        emitter.complete();
                        triggerLongTermMemoryIngestion(streamUserId, sid, userInput, streamMdcSnapshot);
                        triggerShortTermMaintenance(streamUserSnapshot, streamUserId, sid,
                                buildResult.skipMaintenanceCompression(), streamMdcSnapshot);
                    } finally {
                        UserContextHolder.clear();
                        MDC.clear();
                    }
                }
        );

        return sid;
    }

    /* ---------------- private helpers ---------------- */

    /**
     * 构造模型上下文。短期记忆经 {@link ShortTermMemoryService} 三级读穿获取：
     * L1(Redis) 命中直接用；否则 L2 快照回填；再否则冷会话回源 L3 全量历史（必要时同步重算摘要）。
     * <p>每条请求在合并系统段<strong>最前</strong>注入服务器当前时间。RAG 长期记忆片段插在短期摘要之后、滑动窗口之前。
     * 用户消息仍以原始 {@code userInput} 入模（见 streamChat）。</p>
     *
     * @param sid       会话 ID
     * @param userId    当前用户 ID（供 L2 文件实现分区；可能为 null）
     * @param userInput 本轮用户输入
     */
    private BuildMessagesResult buildMessages(String sid, Long userId, String userInput) {
        String clockLine = sessionClockAnchorLine();
        String instruction = properties.getInstruction() == null ? "" : properties.getInstruction().trim();
        String requiredSystemText = instruction.isBlank() ? clockLine : clockLine + "\n\n---\n" + instruction;

        // 三级读穿；冷会话才会调用 L3 加载器（运行在当前 Servlet 线程，UserContext 可用）。
        ShortTermMemoryService.ShortTermMemoryLoadResult memoryResult = shortTermMemoryService.loadForTurnWithMetadata(
                userId, sid, () -> memoryStore.load(sid));
        ShortTermMemorySnapshot snapshot = memoryResult.snapshot();

        String activeModelName = resolveActiveChatModelName();
        int contextWindowTokens = fishLlmProperties.getEffectiveContextWindowTokens(activeModelName);
        BudgetPlan budgetPlan = new ContextBudgetAllocator(
                contextWindowTokens,
                fishLlmProperties.getOutputReserveTokens(),
                fishLlmProperties.getSafetyMarginRatio()
        ).allocate(new BudgetRequest(userInput, requiredSystemText));

        boolean trimmed = budgetPlan.exhausted();
        // 合并为单条 SystemMessage：Alibaba ReactAgent 还会在内部拼接系统位，多条 SystemMessage 会触发 AgentLlmNode 英文 WARN 且不利于模型解析。
        StringBuilder systemBlock = new StringBuilder(requiredSystemText);

        if (snapshot.structuredSummary() != null) {
            String summaryBlock = formatStructuredSummaryForContext(snapshot.structuredSummary(), budgetPlan.summaryBudget());
            if (!summaryBlock.isBlank()) {
                appendSystemSection(systemBlock, summaryBlock);
                log.debug("[ChatService] 使用结构化短期记忆 sid={}", sid);
            } else {
                trimmed = true;
            }
        }

        int effectiveExcerpts = countEffectiveExcerpts(snapshot.keyExcerpts());
        String excerptBlock = formatKeyExcerptsForContext(snapshot.keyExcerpts(), budgetPlan.excerptBudget());
        if (!excerptBlock.isBlank()) {
            appendSystemSection(systemBlock, excerptBlock);
            if (excerptBlockLineCount(excerptBlock) < effectiveExcerpts) {
                trimmed = true;
            }
        } else if (effectiveExcerpts > 0) {
            trimmed = true;
        }

        SessionAgentState agentState = agentStateStore.load(userId, sid);
        if (agentState != null && !"IDLE".equals(agentState.phase())) {
            String stateBlock = "## 当前会话状态\n" + formatAgentState(agentState);
            if (budgetPlan.stateBudget() > 0 && TokenEstimator.estimate(stateBlock) <= budgetPlan.stateBudget()) {
                appendSystemSection(systemBlock, stateBlock);
            } else {
                trimmed = true;
            }
        }

        Optional<String> rag = budgetPlan.ragBudget() <= 0
                ? Optional.empty()
                : longTermRagContextService.buildAugmentation(
                sid, userInput, extractContextHint(snapshot), budgetPlan.ragBudget());
        if (rag.isPresent() && !rag.get().isBlank()) {
            appendSystemSection(systemBlock, rag.get().trim());
            log.debug("[ChatService] 已注入长期记忆 RAG sid={}, blockLen={}", sid, rag.get().length());
        } else if (budgetPlan.ragBudget() <= 0) {
            trimmed = true;
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemBlock.toString()));

        List<ChatMessageDTO> contextMessages = snapshot.recentMessages() == null
                ? List.of() : snapshot.recentMessages();
        List<ChatMessageDTO> trimmedContextMessages = ContextWindowTrimmer.trimMessagesByBudget(
                contextMessages, budgetPlan.windowBudget());
        if (trimmedContextMessages.size() < contextMessages.size()) {
            trimmed = true;
        }
        log.debug("[ChatService] 组装模型上下文 sid={}, contextWindowSize={}, trimmedWindowSize={}",
                sid, contextMessages.size(), trimmedContextMessages.size());
        appendReplayableMessages(messages, trimmedContextMessages);

        int totalInputTokens = estimateMessages(messages) + TokenEstimator.estimate(userInput);
        if (totalInputTokens > budgetPlan.inputBudget()) {
            log.warn("[ChatService] Token 超预算 sid={}, actual={}, budget={}，降级裁剪",
                    sid, totalInputTokens, budgetPlan.inputBudget());
            messages = emergencyTrim(requiredSystemText, userInput, contextMessages, budgetPlan.inputBudget());
            totalInputTokens = estimateMessages(messages) + TokenEstimator.estimate(userInput);
            trimmed = true;
        }
        log.info("[ChatService] Token 预算 sid={}, model={}, used={}/{}, trimmed={}",
                sid, activeModelName == null ? "(unknown)" : activeModelName,
                totalInputTokens, budgetPlan.inputBudget(), trimmed);
        return new BuildMessagesResult(
                messages,
                memoryResult.compressedOnColdPath(),
                totalInputTokens,
                budgetPlan.inputBudget(),
                trimmed
        );
    }

    /**
     * 模型上下文构建结果。布尔标志用于避免冷路径已同步压缩后，onComplete 维护任务再次压缩。
     */
    private record BuildMessagesResult(
            List<Message> messages,
            boolean skipMaintenanceCompression,
            int totalInputTokens,
            int inputBudget,
            boolean trimmed) {
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
     * 追加一个 system 子段，统一用分隔线隔开动态上下文，便于模型识别段落边界。
     */
    private static void appendSystemSection(StringBuilder systemBlock, String section) {
        if (section == null || section.isBlank()) {
            return;
        }
        if (!systemBlock.isEmpty()) {
            systemBlock.append("\n\n---\n");
        }
        systemBlock.append(section.trim());
    }

    /**
     * 二次兜底裁剪：当估算总量仍超预算时，仅保留 P1 系统必需文本、P0 当前输入和尽量多的近期对话。
     */
    private List<Message> emergencyTrim(String requiredSystemText, String userInput,
                                        List<ChatMessageDTO> contextMessages, int inputBudget) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(requiredSystemText));
        int remainingForWindow = inputBudget
                - TokenEstimator.estimate(requiredSystemText)
                - TokenEstimator.estimate(userInput);
        appendReplayableMessages(
                messages,
                ContextWindowTrimmer.trimMessagesByBudget(contextMessages, Math.max(0, remainingForWindow))
        );
        return messages;
    }

    private static int estimateMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return messages.stream()
                .mapToInt(message -> TokenEstimator.estimate(message.getText()))
                .sum();
    }

    /**
     * 根据当前 provider 读取实际模型名，用于匹配 fish.llm.model-context-overrides。
     */
    private String resolveActiveChatModelName() {
        return switch (fishLlmProperties.getChatProvider()) {
            case DEEPSEEK -> environment.getProperty("spring.ai.openai.chat.options.model");
            case OLLAMA -> environment.getProperty("spring.ai.ollama.chat.options.model");
            case DASHSCOPE -> environment.getProperty("spring.ai.dashscope.chat.options.model");
        };
    }

    /**
     * 在预算内保留最新的关键原文片段，并按原 turnIndex 顺序渲染。
     */
    private String formatKeyExcerptsForContext(List<KeyExcerpt> excerpts, int tokenBudget) {
        if (excerpts == null || excerpts.isEmpty() || tokenBudget <= 0) {
            return "";
        }
        List<KeyExcerpt> newestFirst = excerpts.stream()
                .filter(excerpt -> excerpt != null
                        && excerpt.content() != null
                        && !excerpt.content().isBlank())
                .sorted(Comparator.comparingInt(KeyExcerpt::turnIndex).reversed())
                .toList();
        List<KeyExcerpt> kept = new ArrayList<>();
        int used = TokenEstimator.estimate("### 关键历史片段\n");
        for (KeyExcerpt excerpt : newestFirst) {
            String line = renderKeyExcerptLine(excerpt);
            int lineTokens = TokenEstimator.estimate(line);
            if (used + lineTokens > tokenBudget) {
                break;
            }
            used += lineTokens;
            kept.add(excerpt);
        }
        if (kept.isEmpty()) {
            return "";
        }
        kept.sort(Comparator.comparingInt(KeyExcerpt::turnIndex));
        StringBuilder sb = new StringBuilder("### 关键历史片段\n");
        kept.forEach(excerpt -> sb.append(renderKeyExcerptLine(excerpt)));
        return sb.toString();
    }

    private static String renderKeyExcerptLine(KeyExcerpt excerpt) {
        return "- [" + excerpt.role() + "] "
                + excerpt.content()
                + (excerpt.reason() == null || excerpt.reason().isBlank()
                ? "" : "（" + excerpt.reason() + "）")
                + '\n';
    }

    private static int excerptBlockLineCount(String excerptBlock) {
        if (excerptBlock == null || excerptBlock.isBlank()) {
            return 0;
        }
        return (int) excerptBlock.lines()
                .filter(line -> line.startsWith("- ["))
                .count();
    }

    /**
     * 统计 content 非空的有效关键片段数，作为是否触发预算裁剪的基准。
     * <p>用原始 size 作分母会在存在空内容片段时误报 trimmed；这里只计实际可渲染的条目。</p>
     */
    private static int countEffectiveExcerpts(List<KeyExcerpt> excerpts) {
        if (excerpts == null || excerpts.isEmpty()) {
            return 0;
        }
        return (int) excerpts.stream()
                .filter(e -> e != null && e.content() != null && !e.content().isBlank())
                .count();
    }

    /**
     * 对话结束后的短期记忆异步维护：读 L3 全量 → 达阈值则压缩更新 L1 摘要 → 把 L1 刷入 L2 快照。
     * <p>运行在异步线程，必须回放 {@link UserContext}：{@link com.yuyu.fishagent.chat.history.RustFsChatMemoryStore#load}
     * 会校验归属并从 ThreadLocal 取 userId。</p>
     *
     * @param userSnapshot 进入流式前快照的用户上下文（可能为 null）
     * @param userId       当前用户 ID（供 L2 文件实现分区）
     * @param sid          会话 ID
     */
    private void triggerShortTermMaintenance(UserContext userSnapshot, Long userId, String sid,
                                             boolean skipCompressionThisTurn,
                                             Map<String, String> mdcSnapshot) {
        MdcAsync.mdcRunAsync(() -> {
            if (userSnapshot != null) {
                UserContextHolder.set(userSnapshot);
            }
            try {
                if (skipCompressionThisTurn) {
                    log.debug("[ChatService] 本轮冷路径已同步压缩，跳过异步重复压缩 sid={}", sid);
                    shortTermMemoryService.refreshSnapshotFromL1(userId, sid);
                    return;
                }
                if (shortTermMemoryService.shouldLoadFullHistoryForMaintenance(sid)) {
                    List<ChatMessageDTO> full = memoryStore.load(sid);
                    if (full.size() >= memoryProperties.getSummaryTriggerThreshold()) {
                        log.debug("[ChatService] 触发异步记忆压缩 sid={}, historySize={}, threshold={}",
                                sid, full.size(), memoryProperties.getSummaryTriggerThreshold());
                        ShortTermMemorySnapshot current = shortTermMemoryService.getCurrentSnapshot(sid);
                        StructuredSummary currentSummary = current.structuredSummary();
                        List<ChatMessageDTO> newMessages = currentSummary == null
                                ? full
                                : extractNewMessagesAfter(full,
                                current.lastCompressedMessageCount(),
                                current.lastCompressedAt());
                        var compressionResult = memoryCompressionService.compressStructured(
                                sid,
                                currentSummary,
                                newMessages,
                                full,
                                current.incrementalCount()
                        );
                        mergeAgentStateFromCompression(userId, sid, compressionResult);
                    }
                } else {
                    log.debug("[ChatService] L1 窗口远低于压缩阈值，跳过 L3 全量读取 sid={}", sid);
                }
                shortTermMemoryService.refreshSnapshotFromL1(userId, sid);
            } catch (Exception e) {
                log.warn("[ChatService] 短期记忆维护失败 sid={}: {}", sid, e.getMessage());
            } finally {
                UserContextHolder.clear();
            }
        }, mdcSnapshot);
    }

    /**
     * 将结构化摘要格式化为模型上下文文本。只注入仍在进行或暂停的话题，避免已关闭话题占用窗口。
     */
    private String formatStructuredSummaryForContext(StructuredSummary summary, int tokenBudget) {
        if (summary == null || tokenBudget <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## 对话上下文摘要\n");

        if (summary.activeTopics() != null && !summary.activeTopics().isEmpty()) {
            sb.append("### 话题\n");
            for (TopicSegment topic : summary.activeTopics()) {
                if ("CLOSED".equals(topic.status())) {
                    continue;
                }
                sb.append("- **").append(blankToDefault(topic.topic(), "未命名话题")).append("**")
                        .append("（").append(blankToDefault(topic.status(), "ACTIVE")).append("）：")
                        .append(blankToDefault(topic.summary(), "")).append('\n');
            }
            if (TokenEstimator.estimate(sb.toString()) > tokenBudget) {
                return trimTextToBudget(sb.toString(), tokenBudget);
            }
        }

        if (summary.keyEntities() != null && !summary.keyEntities().isEmpty()) {
            sb.append("### 关键实体\n");
            summary.keyEntities().forEach((category, values) -> {
                if (values != null && !values.isEmpty()) {
                    sb.append("- ").append(category).append("：")
                            .append(String.join("、", values)).append('\n');
                }
            });
            if (TokenEstimator.estimate(sb.toString()) > tokenBudget) {
                return trimTextToBudget(sb.toString(), tokenBudget);
            }
        }

        if (summary.pendingIntents() != null && !summary.pendingIntents().isEmpty()) {
            sb.append("### 待办意图\n");
            for (String intent : summary.pendingIntents()) {
                if (intent != null && !intent.isBlank()) {
                    sb.append("- ").append(intent).append('\n');
                }
            }
            if (TokenEstimator.estimate(sb.toString()) > tokenBudget) {
                return trimTextToBudget(sb.toString(), tokenBudget);
            }
        }

        UserSignals signals = summary.userSignals();
        if (signals != null) {
            boolean hasSignals = (signals.expertise() != null && !signals.expertise().isBlank())
                    || (signals.communicationStyle() != null && !signals.communicationStyle().isBlank())
                    || (signals.observedPreferences() != null && !signals.observedPreferences().isEmpty());
            if (hasSignals) {
                sb.append("### 用户画像\n");
                if (signals.expertise() != null && !signals.expertise().isBlank()) {
                    sb.append("- 专业度：").append(signals.expertise()).append('\n');
                }
                if (signals.communicationStyle() != null && !signals.communicationStyle().isBlank()) {
                    sb.append("- 沟通偏好：").append(signals.communicationStyle()).append('\n');
                }
                if (signals.observedPreferences() != null && !signals.observedPreferences().isEmpty()) {
                    sb.append("- 偏好：").append(String.join("、", signals.observedPreferences())).append('\n');
                }
            }
        }
        return trimTextToBudget(sb.toString(), tokenBudget);
    }

    /**
     * Binary-search by character index to keep token estimation under budget.
     */
    private static String trimTextToBudget(String text, int tokenBudget) {
        if (text == null || text.isBlank() || tokenBudget <= 0) {
            return "";
        }
        if (TokenEstimator.estimate(text) <= tokenBudget) {
            return text;
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (TokenEstimator.estimate(text.substring(0, mid)) <= tokenBudget) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, low).stripTrailing();
    }

    /**
     * 将 Agent 过程状态格式化为可注入模型上下文的文本。
     */
    private String formatAgentState(SessionAgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 阶段：").append(phaseLabel(state.phase())).append('\n');

        if (state.activeTasks() != null && !state.activeTasks().isEmpty()) {
            sb.append("- 活跃任务：\n");
            for (ActiveTask task : state.activeTasks()) {
                sb.append("  - ").append(blankToDefault(task.description(), "未命名任务"))
                        .append("（").append(blankToDefault(task.status(), "IN_PROGRESS")).append("）");
                if (task.currentStep() != null && !task.currentStep().isBlank()) {
                    sb.append("，当前：").append(task.currentStep());
                }
                if ("BLOCKED".equals(task.status()) && task.blockedReason() != null && !task.blockedReason().isBlank()) {
                    sb.append("，阻塞原因：").append(task.blockedReason());
                }
                sb.append('\n');
            }
        }

        if (state.lastDetectedIntent() != null && !state.lastDetectedIntent().isBlank()) {
            sb.append("- 上轮意图：").append(state.lastDetectedIntent()).append('\n');
        }

        if (state.recentTools() != null && !state.recentTools().isEmpty()) {
            sb.append("- 近期工具调用：");
            state.recentTools().stream().limit(5).forEach(tool ->
                    sb.append(tool.toolName()).append('(')
                            .append(tool.succeeded() ? "成功" : "失败").append(") "));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String phaseLabel(String phase) {
        if (phase == null) {
            return "空闲";
        }
        return switch (phase) {
            case "EXPLORING" -> "探索中";
            case "EXECUTING" -> "任务执行中";
            case "REVIEWING" -> "回顾中";
            default -> "空闲";
        };
    }

    /**
     * 规则驱动地保存本轮 ReAct 工具调用记录。
     */
    private void updateAgentStateByRules(Long userId, String sid, List<NodeOutput> nodes) {
        try {
            SessionAgentState current = agentStateStore.load(userId, sid);
            SessionAgentState updated = agentStateUpdater.updateToolRecords(current, nodes);
            agentStateStore.save(userId, sid, updated);
        } catch (Exception e) {
            log.warn("[ChatService] Agent 状态规则更新失败 sid={}: {}", sid, e.getMessage());
        }
    }

    /**
     * 复用结构化压缩中的 agent_state 推断结果，补全任务阶段、意图和活跃任务。
     */
    private void mergeAgentStateFromCompression(Long userId, String sid,
                                                MemoryResponseParser.StructuredCompressionResult compressionResult) {
        if (compressionResult == null || compressionResult.agentStateNode() == null) {
            return;
        }
        try {
            SessionAgentState inferred = objectMapper.treeToValue(
                    compressionResult.agentStateNode(), SessionAgentState.class);
            SessionAgentState current = agentStateStore.load(userId, sid);
            SessionAgentState merged = agentStateUpdater.mergeWithInferred(current, inferred);
            agentStateStore.save(userId, sid, merged);
            log.debug("[ChatService] Agent 状态已从压缩结果更新 sid={}, phase={}", sid, merged.phase());
        } catch (Exception e) {
            log.warn("[ChatService] Agent 状态 LLM 推断解析失败 sid={}: {}", sid, e.getMessage());
        }
    }

    /**
     * 提取上次压缩游标之后的新增消息，避免增量压缩重复消费旧历史。
     */
    private List<ChatMessageDTO> extractNewMessagesAfter(List<ChatMessageDTO> full,
                                                         int lastCompressedMessageCount,
                                                         long lastCompressedAt) {
        if (full == null || full.isEmpty()) {
            return List.of();
        }
        if (lastCompressedMessageCount > 0 && lastCompressedMessageCount <= full.size()) {
            return new ArrayList<>(full.subList(lastCompressedMessageCount, full.size()));
        }
        if (lastCompressedAt <= 0) {
            return full;
        }
        return full.stream()
                .filter(message -> message != null && message.getCreatedAt() > lastCompressedAt)
                .toList();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 从短期记忆快照中提取简洁上下文提示，供查询扩展理解用户意图。
     * 只取活跃话题摘要，控制 ~200 字符以内，避免增加扩展 LLM 调用的 token 消耗。
     */
    private String extractContextHint(ShortTermMemorySnapshot snapshot) {
        if (snapshot == null || snapshot.structuredSummary() == null) {
            return null;
        }
        StructuredSummary summary = snapshot.structuredSummary();
        if (summary.activeTopics() == null || summary.activeTopics().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (TopicSegment topic : summary.activeTopics()) {
            if ("CLOSED".equals(topic.status())) continue;
            if (sb.length() > 0) sb.append("；");
            String name = (topic.topic() == null || topic.topic().isBlank()) ? "未命名" : topic.topic();
            String text = (topic.summary() == null || topic.summary().isBlank()) ? "" : topic.summary();
            sb.append(name).append("：").append(text);
            if (sb.length() > 200) break;
        }
        return sb.isEmpty() ? null : sb.toString();
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
    private void triggerLongTermMemoryIngestion(Long userId, String sid, String userInput,
                                                Map<String, String> mdcSnapshot) {
        log.debug("[ChatService] 触发异步长期记忆主动录入 uid={}, sid={}, inputLen={}",
                userId, sid, userInput == null ? 0 : userInput.length());
        // 捕获 userId：异步线程中 ThreadLocal 不可用
        MdcAsync.mdcRunAsync(() -> longTermMemoryIngestionService.ingestFromUserInput(userId, sid, userInput), mdcSnapshot);
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
        if (node == null) {
            log.debug("[ChatService] node: null");
            return;
        }
        buf.nodes.add(node);
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
        final List<NodeOutput> nodes = new ArrayList<>();
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
