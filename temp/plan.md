# 短期记忆三级读穿 + 写穿 实现计划

> **For agentic workers (codex):** 按任务顺序逐个实现,每个任务遵循 TDD(先写失败测试 → 跑红 → 最小实现 → 跑绿 → 提交)。步骤用 `- [ ]` 复选框跟踪。所有路径、签名、代码均已给全,**不要自行改名或省略**。

**Goal:** 把短期记忆做成 L1(Redis,每轮主存)→ L2(对象存储快照,Redis 失效兜底)→ L3(对象存储全量正文,冷会话重算/前端显示全部)三级结构;对话热路径(首字)只读 Redis,对话结束后同步刷新 L1 窗口、异步刷新 L2 快照与压缩摘要。

**Architecture:** 新增 L2 快照存储 SPI(`ShortTermSnapshotStore`,RustFs/File 双实现)与协调器(`ShortTermMemoryService`)统一编排读穿/写穿;`ChatService` 读路径改走协调器(热路径不再每轮全量读 L3),写路径拆分为「同步落 L3 正文 + 同步追加 L1 窗口」与「异步刷 L2 快照 + 异步压缩」;`MemoryCompressionService` 保持不变(仍只写 L1),L2 由协调器在压缩后从 L1 同步过去。

**Tech Stack:** Java 21 / Spring Boot 3.5 / MinIO(RustFS)/ Redis(Lettuce, StringRedisTemplate)/ Jackson / JUnit 5 + Mockito + AssertJ(`spring-boot-starter-test`)/ Maven。

---

## 背景与现状(实现前必读)

- 短期记忆现仅存 Redis,且**只在压缩(≥30 条)时才写**;前 29 条 Redis 为空,热路径每轮都同步全量读 `RustFsChatMemoryStore.load`。
- TTL(默认 30 天)到期后 summary 永久丢失,无对象存储兜底。
- `chat_metadata.message_count` 字段存在但**从未维护**,不可用于阈值。
- 相关现有文件:
  - `src/main/java/com/yuyu/fishagent/memory/shortterm/ShortTermMemoryStore.java`(L1 SPI:`save/load`)
  - `src/main/java/com/yuyu/fishagent/memory/shortterm/RedisShortTermMemoryStore.java`(L1 Redis 实现)
  - `src/main/java/com/yuyu/fishagent/memory/shortterm/ShortTermMemorySnapshot.java`(record:`summary` + `recentMessages`)
  - `src/main/java/com/yuyu/fishagent/memory/config/MemoryProperties.java`(`fish.memory.*`)
  - `src/main/java/com/yuyu/fishagent/memory/MemoryCompressionService.java`(压缩→写 L1)
  - `src/main/java/com/yuyu/fishagent/chat/ChatService.java`(对话编排,需重构)
  - `src/main/java/com/yuyu/fishagent/chat/history/ChatMemoryStore.java` / `RustFsChatMemoryStore.java`(L3 全量正文)
  - `src/main/java/com/yuyu/fishagent/rag/service/RustFsService.java`(`putChatJson/getChatJsonOrNull/deleteChatJson`,桶 `fish-chat`)

## 目标行为

**读路径(每轮对话开始,`buildMessages` 内,运行在 Servlet 线程,UserContext 可用):**
1. L1(Redis)命中(summary 非空 或 window 非空)→ 直接用,**不读 L3**。
2. L1 未命中 → 读 L2 快照 `{sid}.stm.json`;命中 → 回填 L1 → 用。
3. L2 也未命中(冷会话)→ 读 L3 全量:
   - `full.size() >= summaryTriggerThreshold` 且 `recomputeOnCold=true` → **同步**调 `MemoryCompressionService.compress`(写 L1)→ 回填 L2 → 用(此处会阻塞首字 1–5s,仅老会话首访/极少发生)。
   - 否则 → 取尾部 `shortTermWindowSize` 条为 window、summary 空 → 回填 L1+L2 → 用。

**写路径(SSE 流 `onComplete`):**
1. **同步**:`persist(sid,...)` 落 L3 全量正文(沿用现状,事实来源强一致)。
2. **同步**:`appendTurnToL1`——把本轮 user/assistant 追加进 L1 窗口并裁剪到 `shortTermWindowSize`(一次 Redis SET,保证下一轮热路径读得到本轮)。
3. 发送 `done`、`emitter.complete()`。
4. **异步**:长期记忆抽取(沿用)。
5. **异步(单任务,需回放 UserContext)**:读 L3 全量 → 若 `size>=threshold` 调 `compress`(更新 L1 summary+window)→ `refreshSnapshotFromL1`(把 L1 拷到 L2)。

**删除会话:** 先 `memoryStore.clear`(含归属校验)→ 再清 L1 + L2。

**降级:** Redis 不可用 → L1 返回空快照、写入静默跳过;RustFS 关闭(`fish.rustfs.enabled=false`)→ L2 用文件实现;对象存储不可用 → L2 读返回空、写记 warn,不影响对话。

---

## 文件结构(创建/修改一览)

- **Modify** `memory/config/MemoryProperties.java`:新增内嵌 `SnapshotProperties snapshot`(`enabled`、`recomputeOnCold`)。
- **Modify** `memory/shortterm/ShortTermMemoryStore.java`:接口新增 `void clear(String sessionId)`。
- **Modify** `memory/shortterm/RedisShortTermMemoryStore.java`:实现 `clear`(删 summary+messages 两个 key)。
- **Create** `memory/shortterm/ShortTermSnapshotStore.java`:L2 SPI(`load/save/clear`,以 `ShortTermMemorySnapshot` 为载荷)。
- **Create** `memory/shortterm/RustFsShortTermSnapshotStore.java`:L2 RustFs 实现(key `{sid}.stm.json`,复用 `RustFsService`,注入 Spring `ObjectMapper`)。
- **Create** `memory/shortterm/FileShortTermSnapshotStore.java`:L2 文件兜底实现(`{historyDir}/{userId}/{sid}.stm.json`)。
- **Create** `memory/shortterm/ShortTermMemoryService.java`:协调器(`loadForTurn` / `appendTurnToL1` / `refreshSnapshotFromL1` / `clear`)。
- **Create** `src/test/java/com/yuyu/fishagent/memory/shortterm/ShortTermMemoryServiceTest.java`:协调器单测。
- **Modify** `chat/ChatService.java`:读路径走协调器、写路径拆同步/异步、`deleteSession` 连带清 L1+L2、删除无用的 `shortTermMemoryStore` 字段与 `recentMessages` 私有方法。

---

## Task 1：MemoryProperties 新增快照子配置

**Files:**
- Modify: `src/main/java/com/yuyu/fishagent/memory/config/MemoryProperties.java`

- [ ] **Step 1：在 `MemoryProperties` 中新增内嵌类与字段**

在 `MemoryChatProperties` 内嵌类之后、`shortTermWindowSize` 字段之前(或类体内任意合适位置)新增:

```java
    /**
     * 短期记忆对象存储快照子配置（{@code fish.memory.snapshot.*}）。
     * <p>作为 Redis 失效（TTL 到期 / 不可用）时的兜底兜源。</p>
     */
    @Data
    public static class SnapshotProperties {
        /** 是否启用 L2 对象存储快照兜底。false 时仅用 Redis，Redis 失效则回退全量历史窗口、摘要丢失。 */
        private boolean enabled = true;
        /** 冷会话（L1+L2 均未命中）且历史达到压缩阈值时，是否同步重算摘要（会阻塞首字 1-5s）。 */
        private boolean recomputeOnCold = true;
    }
```

并在类体末尾(`chat` 字段附近)新增字段:

```java
    /**
     * {@code fish.memory.snapshot}：短期记忆对象存储快照兜底参数。
     */
    private SnapshotProperties snapshot = new SnapshotProperties();
```

- [ ] **Step 2：编译验证**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS(无报错)。

- [ ] **Step 3：提交**

```bash
git add src/main/java/com/yuyu/fishagent/memory/config/MemoryProperties.java
git commit -m "feat(memory): add fish.memory.snapshot config (L2 fallback toggle)"
```

---

## Task 2：L1 SPI 增加 clear 能力

**Files:**
- Modify: `src/main/java/com/yuyu/fishagent/memory/shortterm/ShortTermMemoryStore.java`
- Modify: `src/main/java/com/yuyu/fishagent/memory/shortterm/RedisShortTermMemoryStore.java`

- [ ] **Step 1：接口新增方法**

在 `ShortTermMemoryStore` 接口中 `load` 方法之后新增:

```java
    /**
     * 删除某会话的短期记忆（摘要 + 最近窗口）。存储不可用时应静默跳过。
     *
     * @param sessionId 会话 ID
     */
    void clear(String sessionId);
```

- [ ] **Step 2：Redis 实现 clear**

在 `RedisShortTermMemoryStore` 的 `load` 方法之后、`summaryKey` 之前新增:

```java
    /**
     * 删除该会话的摘要与窗口两个 key。Redis 不可用时静默跳过。
     */
    @Override
    public void clear(String sessionId) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.debug("[RedisShortTermMemoryStore] RedisTemplate 不可用，跳过短期记忆删除 sid={}", sessionId);
            return;
        }
        try {
            redisTemplate.delete(summaryKey(sessionId));
            redisTemplate.delete(messagesKey(sessionId));
            log.debug("[RedisShortTermMemoryStore] 短期记忆已删除 sid={}", sessionId);
        } catch (Exception e) {
            log.warn("[RedisShortTermMemoryStore] 删除短期记忆失败 sid={}: {}", sessionId, e.getMessage());
        }
    }
```

- [ ] **Step 3：编译验证**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4：提交**

```bash
git add src/main/java/com/yuyu/fishagent/memory/shortterm/ShortTermMemoryStore.java src/main/java/com/yuyu/fishagent/memory/shortterm/RedisShortTermMemoryStore.java
git commit -m "feat(memory): add clear() to short-term L1 store"
```

---

## Task 3：L2 快照存储 SPI 与双实现

**Files:**
- Create: `src/main/java/com/yuyu/fishagent/memory/shortterm/ShortTermSnapshotStore.java`
- Create: `src/main/java/com/yuyu/fishagent/memory/shortterm/RustFsShortTermSnapshotStore.java`
- Create: `src/main/java/com/yuyu/fishagent/memory/shortterm/FileShortTermSnapshotStore.java`

- [ ] **Step 1：定义 L2 SPI**

创建 `ShortTermSnapshotStore.java`:

```java
package com.yuyu.fishagent.memory.shortterm;

/**
 * 短期记忆 L2 快照存储 SPI（对象存储 / 文件兜底）。
 * <p>作为 Redis(L1) 失效时的兜源：持久保存 summary + 最近窗口，无 TTL。
 * 不存在或存储不可用时应返回空快照。</p>
 */
public interface ShortTermSnapshotStore {

    /**
     * 读取会话的短期记忆快照。不存在 / 不可用时返回空快照（不可返回 null）。
     *
     * @param userId    会话所属用户（文件实现据此分区；对象存储实现可忽略）
     * @param sessionId 会话 ID
     */
    ShortTermMemorySnapshot load(Long userId, String sessionId);

    /**
     * 覆盖写会话的短期记忆快照。失败时记录日志，不抛出。
     */
    void save(Long userId, String sessionId, ShortTermMemorySnapshot snapshot);

    /**
     * 删除会话的短期记忆快照。失败时记录日志，不抛出。
     */
    void clear(Long userId, String sessionId);
}
```

- [ ] **Step 2：RustFs 实现**

创建 `RustFsShortTermSnapshotStore.java`(注入 Spring 容器的 `ObjectMapper`,key 用 `{sid}.stm.json`,与对话正文同桶 `fish-chat`):

```java
package com.yuyu.fishagent.memory.shortterm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.rag.service.RustFsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 RustFS 的短期记忆 L2 快照存储；对象键 {@code {sessionId}.stm.json}，与对话正文 {@code {sessionId}.json} 同桶。
 * <p>sessionId 为全局唯一 UUID，故对象键不带 userId；归属校验在对话主链路已完成。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fish.rustfs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RustFsShortTermSnapshotStore implements ShortTermSnapshotStore {

    private final RustFsService rustFsService;
    /** 注入 Spring 管理的 ObjectMapper：已注册 ParameterNamesModule，可正确反序列化 record。 */
    private final ObjectMapper objectMapper;

    @Override
    public ShortTermMemorySnapshot load(Long userId, String sessionId) {
        try {
            byte[] bytes = rustFsService.getChatJsonOrNull(objectKey(sessionId));
            if (bytes == null || bytes.length == 0) {
                return new ShortTermMemorySnapshot("", List.of());
            }
            return objectMapper.readValue(bytes, ShortTermMemorySnapshot.class);
        } catch (Exception e) {
            log.warn("[RustFsShortTermSnapshotStore] 读取快照失败 sid={}: {}", sessionId, e.getMessage());
            return new ShortTermMemorySnapshot("", List.of());
        }
    }

    @Override
    public void save(Long userId, String sessionId, ShortTermMemorySnapshot snapshot) {
        try {
            byte[] data = objectMapper.writeValueAsBytes(snapshot);
            rustFsService.putChatJson(objectKey(sessionId), data);
            log.debug("[RustFsShortTermSnapshotStore] 快照写入完成 sid={}, summaryLen={}, window={}",
                    sessionId,
                    snapshot.summary() == null ? 0 : snapshot.summary().length(),
                    snapshot.recentMessages() == null ? 0 : snapshot.recentMessages().size());
        } catch (Exception e) {
            log.warn("[RustFsShortTermSnapshotStore] 写入快照失败 sid={}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void clear(Long userId, String sessionId) {
        try {
            rustFsService.deleteChatJson(objectKey(sessionId));
        } catch (Exception e) {
            log.warn("[RustFsShortTermSnapshotStore] 删除快照失败 sid={}: {}", sessionId, e.getMessage());
        }
    }

    private static String objectKey(String sessionId) {
        if (sessionId == null || sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("invalid sessionId");
        }
        return sessionId + ".stm.json";
    }
}
```

- [ ] **Step 3：文件兜底实现**

创建 `FileShortTermSnapshotStore.java`(仅在 `fish.rustfs.enabled=false` 时激活,路径 `{historyDir}/{userId}/{sid}.stm.json`):

```java
package com.yuyu.fishagent.memory.shortterm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.agent.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 基于本地文件的短期记忆 L2 快照兜底：{@code {historyDir}/{userId}/{sessionId}.stm.json}。
 * <p>仅在 {@code fish.rustfs.enabled=false} 时激活，与 {@code UserScopedFileChatMemoryStore} 同根目录。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fish.rustfs", name = "enabled", havingValue = "false")
public class FileShortTermSnapshotStore implements ShortTermSnapshotStore {

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    private Path baseDir;

    @PostConstruct
    public void init() throws IOException {
        this.baseDir = Path.of(properties.getHistoryDir()).toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        log.info("[FileShortTermSnapshotStore] 短期记忆快照根目录: {}", baseDir);
    }

    @Override
    public ShortTermMemorySnapshot load(Long userId, String sessionId) {
        Path file = snapshotFile(userId, sessionId);
        if (file == null || !Files.exists(file)) {
            return new ShortTermMemorySnapshot("", List.of());
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return new ShortTermMemorySnapshot("", List.of());
            }
            return objectMapper.readValue(bytes, ShortTermMemorySnapshot.class);
        } catch (IOException e) {
            log.warn("[FileShortTermSnapshotStore] 读取快照失败 sid={}: {}", sessionId, e.getMessage());
            return new ShortTermMemorySnapshot("", List.of());
        }
    }

    @Override
    public void save(Long userId, String sessionId, ShortTermMemorySnapshot snapshot) {
        Path file = snapshotFile(userId, sessionId);
        if (file == null) {
            log.warn("[FileShortTermSnapshotStore] 缺少 userId，跳过快照写入 sid={}", sessionId);
            return;
        }
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            byte[] data = objectMapper.writeValueAsBytes(snapshot);
            Files.write(tmp, data);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignore) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("[FileShortTermSnapshotStore] 写入快照失败 sid={}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void clear(Long userId, String sessionId) {
        Path file = snapshotFile(userId, sessionId);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[FileShortTermSnapshotStore] 删除快照失败 sid={}: {}", sessionId, e.getMessage());
        }
    }

    private Path snapshotFile(Long userId, String sessionId) {
        if (userId == null) {
            return null;
        }
        if (sessionId == null || sessionId.isBlank() || sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("invalid sessionId: " + sessionId);
        }
        return baseDir.resolve(String.valueOf(userId)).resolve(sessionId + ".stm.json");
    }
}
```

> 注意:`AgentProperties.getHistoryDir()` 已被 `UserScopedFileChatMemoryStore` 使用,确认存在该 getter(若不存在,实现阶段应报错并停下来确认,不要新增配置项)。

- [ ] **Step 4：编译验证**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/yuyu/fishagent/memory/shortterm/ShortTermSnapshotStore.java src/main/java/com/yuyu/fishagent/memory/shortterm/RustFsShortTermSnapshotStore.java src/main/java/com/yuyu/fishagent/memory/shortterm/FileShortTermSnapshotStore.java
git commit -m "feat(memory): add L2 short-term snapshot store (RustFs + file)"
```

---

## Task 4：协调器 ShortTermMemoryService（核心,先写测试）

**Files:**
- Create: `src/main/java/com/yuyu/fishagent/memory/shortterm/ShortTermMemoryService.java`
- Test: `src/test/java/com/yuyu/fishagent/memory/shortterm/ShortTermMemoryServiceTest.java`

- [ ] **Step 1：写失败测试**

创建 `ShortTermMemoryServiceTest.java`:

```java
package com.yuyu.fishagent.memory.shortterm;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.memory.MemoryCompressionService;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShortTermMemoryServiceTest {

    @Mock ShortTermMemoryStore l1;
    @Mock ShortTermSnapshotStore l2;
    @Mock MemoryCompressionService compression;

    MemoryProperties props;
    ShortTermMemoryService service;

    @BeforeEach
    void setUp() {
        props = new MemoryProperties();
        props.setShortTermWindowSize(4);
        props.setSummaryTriggerThreshold(6);
        service = new ShortTermMemoryService(l1, l2, compression, props);
    }

    private static ChatMessageDTO user(String c) { return ChatMessageDTO.of("user", c); }
    private static ChatMessageDTO assistant(String c) { return ChatMessageDTO.of("assistant", c); }

    @Test
    void loadForTurn_returnsL1WhenHit_withoutTouchingL2OrHistory() {
        when(l1.load("s1")).thenReturn(new ShortTermMemorySnapshot("sum", List.of(user("hi"))));

        ShortTermMemorySnapshot snap = service.loadForTurn(7L, "s1", () -> { throw new AssertionError("不应读 L3"); });

        assertThat(snap.summary()).isEqualTo("sum");
        verify(l2, never()).load(any(), anyString());
    }

    @Test
    void loadForTurn_backfillsL1FromL2OnRedisMiss() {
        when(l1.load("s1")).thenReturn(new ShortTermMemorySnapshot("", List.of()));
        when(l2.load(7L, "s1")).thenReturn(new ShortTermMemorySnapshot("l2sum", List.of(user("a"))));

        ShortTermMemorySnapshot snap = service.loadForTurn(7L, "s1", () -> { throw new AssertionError("不应读 L3"); });

        assertThat(snap.summary()).isEqualTo("l2sum");
        verify(l1).save("s1", "l2sum", List.of(user("a")));
    }

    @Test
    void loadForTurn_coldWithoutEnoughHistory_usesWindowOnly() {
        when(l1.load("s1")).thenReturn(new ShortTermMemorySnapshot("", List.of()));
        when(l2.load(7L, "s1")).thenReturn(new ShortTermMemorySnapshot("", List.of()));
        List<ChatMessageDTO> full = List.of(user("u1"), assistant("a1"), user("u2"));

        ShortTermMemorySnapshot snap = service.loadForTurn(7L, "s1", () -> full);

        assertThat(snap.summary()).isEmpty();
        assertThat(snap.recentMessages()).containsExactly(user("u1"), assistant("a1"), user("u2"));
        verify(compression, never()).compress(any());
        verify(l1).save(eq("s1"), eq(""), any());
        verify(l2).save(eq(7L), eq("s1"), any());
    }

    @Test
    void loadForTurn_coldWithEnoughHistory_recomputesSummary() {
        when(l1.load("s1"))
                .thenReturn(new ShortTermMemorySnapshot("", List.of()))      // 首次读：未命中
                .thenReturn(new ShortTermMemorySnapshot("recomputed", List.of(user("u6")))); // 压缩后再读
        when(l2.load(7L, "s1")).thenReturn(new ShortTermMemorySnapshot("", List.of()));
        List<ChatMessageDTO> full = new ArrayList<>(
                IntStream.rangeClosed(1, 6).mapToObj(i -> user("m" + i)).toList());

        ShortTermMemorySnapshot snap = service.loadForTurn(7L, "s1", () -> full);

        assertThat(snap.summary()).isEqualTo("recomputed");
        verify(compression).compress(any());
        verify(l2).save(eq(7L), eq("s1"), any());
    }

    @Test
    void appendTurnToL1_appendsAndTrimsToWindow() {
        when(l1.load("s1")).thenReturn(new ShortTermMemorySnapshot(
                "sum", new ArrayList<>(List.of(user("u1"), assistant("a1"), user("u2")))));

        service.appendTurnToL1("s1", user("u3"), assistant("a3"));

        ArgumentCaptor<List<ChatMessageDTO>> cap = ArgumentCaptor.forClass(List.class);
        verify(l1).save(eq("s1"), eq("sum"), cap.capture());
        // 原 3 条 + 新 2 条 = 5 条，裁剪到 windowSize=4，保留最后 4 条
        assertThat(cap.getValue()).containsExactly(assistant("a1"), user("u2"), user("u3"), assistant("a3"));
    }

    @Test
    void clear_clearsBothLayers() {
        service.clear(7L, "s1");
        verify(l1).clear("s1");
        verify(l2).clear(7L, "s1");
    }
}
```

> 该测试依赖 `ChatMessageDTO` 的 `equals`(用于 `containsExactly`)。若 `ChatMessageDTO` 未实现 `equals`(非 record / 无 `@EqualsAndHashCode`),实现阶段把这些断言改为按 `getContent()`/`getRole()` 提取比较(参考 `MemoryCompressionServiceTest` 的 `extracting(ChatMessageDTO::getContent)`),不要给生产类强加 `equals`。

- [ ] **Step 2：跑测试,确认编译失败/红**

Run: `mvn -q -Dtest=ShortTermMemoryServiceTest test`
Expected: 编译失败(`ShortTermMemoryService` 不存在)。

- [ ] **Step 3：实现协调器**

创建 `ShortTermMemoryService.java`:

```java
package com.yuyu.fishagent.memory.shortterm;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.memory.MemoryCompressionService;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.memory.dto.MemoryCompressionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 短期记忆三级协调器：编排 L1(Redis) → L2(对象存储快照) → L3(全量历史) 的读穿与写穿。
 * <ul>
 *   <li>{@link #loadForTurn} 读穿：L1 命中直接用；否则 L2 回填 L1；再否则冷会话读 L3，必要时同步重算摘要。</li>
 *   <li>{@link #appendTurnToL1} 同步把本轮消息追加进 L1 窗口（保证下一轮热路径可见）。</li>
 *   <li>{@link #refreshSnapshotFromL1} 把 L1 现状拷入 L2 快照（异步调用）。</li>
 *   <li>{@link #clear} 删除 L1 + L2。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortTermMemoryService {

    private final ShortTermMemoryStore l1;
    private final ShortTermSnapshotStore l2;
    private final MemoryCompressionService compression;
    private final MemoryProperties properties;

    /**
     * 读穿：返回本轮对话使用的短期记忆快照。运行在 Servlet 线程（UserContext 可用）。
     *
     * @param userId            会话所属用户（供 L2 文件实现分区）
     * @param sessionId         会话 ID
     * @param fullHistoryLoader 冷会话时才会被调用的 L3 全量历史加载器
     */
    public ShortTermMemorySnapshot loadForTurn(Long userId, String sessionId,
                                               Supplier<List<ChatMessageDTO>> fullHistoryLoader) {
        ShortTermMemorySnapshot l1Snap = l1.load(sessionId);
        if (isNonEmpty(l1Snap)) {
            return l1Snap;
        }

        if (properties.getSnapshot().isEnabled()) {
            ShortTermMemorySnapshot l2Snap = l2.load(userId, sessionId);
            if (isNonEmpty(l2Snap)) {
                l1.save(sessionId, l2Snap.summary(), l2Snap.recentMessages()); // 回填 L1
                log.debug("[ShortTermMemoryService] L2 命中并回填 L1 sid={}", sessionId);
                return l2Snap;
            }
        }

        List<ChatMessageDTO> full = fullHistoryLoader.get();
        if (full == null || full.isEmpty()) {
            return new ShortTermMemorySnapshot("", List.of());
        }

        int windowSize = properties.getShortTermWindowSize();
        if (properties.getSnapshot().isRecomputeOnCold()
                && full.size() >= properties.getSummaryTriggerThreshold()) {
            try {
                compression.compress(new MemoryCompressionRequest(sessionId, full)); // 写 L1
                ShortTermMemorySnapshot recomputed = l1.load(sessionId);
                if (properties.getSnapshot().isEnabled() && isNonEmpty(recomputed)) {
                    l2.save(userId, sessionId, recomputed);
                }
                log.debug("[ShortTermMemoryService] 冷会话同步重算完成 sid={}, historySize={}", sessionId, full.size());
                return recomputed;
            } catch (Exception e) {
                log.warn("[ShortTermMemoryService] 冷会话重算失败，降级为窗口 sid={}: {}", sessionId, e.getMessage());
                // 落到下方窗口降级
            }
        }

        List<ChatMessageDTO> window = tail(full, windowSize);
        ShortTermMemorySnapshot snap = new ShortTermMemorySnapshot("", window);
        l1.save(sessionId, "", window);
        if (properties.getSnapshot().isEnabled()) {
            l2.save(userId, sessionId, snap);
        }
        log.debug("[ShortTermMemoryService] 冷会话窗口降级 sid={}, windowSize={}", sessionId, window.size());
        return snap;
    }

    /**
     * 同步把本轮 user/assistant 追加进 L1 窗口并裁剪到窗口大小。摘要保持不变。
     */
    public void appendTurnToL1(String sessionId, ChatMessageDTO userMsg, ChatMessageDTO assistantMsg) {
        ShortTermMemorySnapshot current = l1.load(sessionId);
        List<ChatMessageDTO> window = new ArrayList<>(
                current.recentMessages() == null ? List.of() : current.recentMessages());
        if (userMsg != null) {
            window.add(userMsg);
        }
        if (assistantMsg != null) {
            window.add(assistantMsg);
        }
        l1.save(sessionId, current.summary(), tail(window, properties.getShortTermWindowSize()));
    }

    /**
     * 把 L1 现状（摘要 + 窗口）拷入 L2 快照。异步调用，失败仅记日志。
     */
    public void refreshSnapshotFromL1(Long userId, String sessionId) {
        if (!properties.getSnapshot().isEnabled()) {
            return;
        }
        ShortTermMemorySnapshot s = l1.load(sessionId);
        if (!isNonEmpty(s)) {
            return;
        }
        l2.save(userId, sessionId, s);
    }

    /**
     * 删除 L1 + L2 短期记忆。
     */
    public void clear(Long userId, String sessionId) {
        try {
            l1.clear(sessionId);
        } catch (Exception e) {
            log.warn("[ShortTermMemoryService] 清理 L1 失败 sid={}: {}", sessionId, e.getMessage());
        }
        if (properties.getSnapshot().isEnabled()) {
            try {
                l2.clear(userId, sessionId);
            } catch (Exception e) {
                log.warn("[ShortTermMemoryService] 清理 L2 失败 sid={}: {}", sessionId, e.getMessage());
            }
        }
    }

    private static boolean isNonEmpty(ShortTermMemorySnapshot snap) {
        if (snap == null) {
            return false;
        }
        boolean hasSummary = snap.summary() != null && !snap.summary().isBlank();
        boolean hasWindow = snap.recentMessages() != null && !snap.recentMessages().isEmpty();
        return hasSummary || hasWindow;
    }

    private static List<ChatMessageDTO> tail(List<ChatMessageDTO> list, int windowSize) {
        if (list == null || list.isEmpty() || windowSize <= 0) {
            return List.of();
        }
        int fromIndex = Math.max(0, list.size() - windowSize);
        return new ArrayList<>(list.subList(fromIndex, list.size()));
    }
}
```

- [ ] **Step 4：跑测试,确认绿**

Run: `mvn -q -Dtest=ShortTermMemoryServiceTest test`
Expected: PASS(若因 `ChatMessageDTO` 无 `equals` 导致 `containsExactly` 失败,按 Step 1 注释改为 `extracting(...)` 比较后再跑绿)。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/yuyu/fishagent/memory/shortterm/ShortTermMemoryService.java src/test/java/com/yuyu/fishagent/memory/shortterm/ShortTermMemoryServiceTest.java
git commit -m "feat(memory): add ShortTermMemoryService tiered coordinator (L1/L2/L3)"
```

---

## Task 5：ChatService 接入协调器（读穿 + 写穿 + 删除联动）

**Files:**
- Modify: `src/main/java/com/yuyu/fishagent/chat/ChatService.java`

> 本任务为重构,逐处替换。改完整体编译并跑全量测试。

- [ ] **Step 1：调整依赖字段**

在字段区:删除 `private final ShortTermMemoryStore shortTermMemoryStore;`,新增 `private final ShortTermMemoryService shortTermMemoryService;`。
同步更新 import:删除 `import com.yuyu.fishagent.memory.shortterm.ShortTermMemoryStore;`,新增 `import com.yuyu.fishagent.memory.shortterm.ShortTermMemoryService;`。
保留 `import com.yuyu.fishagent.memory.shortterm.ShortTermMemorySnapshot;`、`memoryStore`、`memoryCompressionService`、`memoryProperties`、`MemoryCompressionRequest`、`UserContext`/`UserContextHolder` 等现有 import。

- [ ] **Step 2：`streamChat` 去掉热路径全量读,改传 userId 给 `buildMessages`**

把原:

```java
        final List<ChatMessageDTO> historyDtos;
        final List<Message> messages;
        try {
            // 1. 组装上下文：文件历史仍是事实来源，模型上下文使用“短期摘要 + 滑动窗口”控制长度。
            historyDtos = memoryStore.load(sid);
            messages = buildMessages(sid, historyDtos, userInput);
            messages.add(new UserMessage(userInput));
        } catch (Exception e) {
```

替换为:

```java
        final List<Message> messages;
        try {
            // 1. 组装上下文：热路径仅读 L1(Redis)；L1/L2 均未命中才由协调器回源 L3 全量历史。
            messages = buildMessages(sid, streamUserId, userInput);
            messages.add(new UserMessage(userInput));
        } catch (Exception e) {
```

- [ ] **Step 3：重写 `onComplete` 回调(同步落 L3 + 同步追加 L1,异步维护)**

把原 `() -> { ... }`(`subscribe` 的第三个参数,行约 219–238)整体替换为:

```java
                () -> {
                    String full = assistantBuf.full.toString().trim();
                    if (streamUserSnapshot != null) {
                        UserContextHolder.set(streamUserSnapshot);
                    }
                    try {
                        ChatMessageDTO userMsg = ChatMessageDTO.of("user", userInput);
                        ChatMessageDTO assistantMsg = full.isBlank() ? null : ChatMessageDTO.of("assistant", full);
                        try {
                            persist(sid, userInput, full);                          // L3 全量正文：同步落盘（事实来源）
                            shortTermMemoryService.appendTurnToL1(sid, userMsg, assistantMsg); // L1 窗口：同步追加（下一轮热路径可见）
                        } catch (Exception e) {
                            log.error("[ChatService] 持久化失败 sid={}: {}", sid, e.getMessage(), e);
                            // persist 失败不阻塞 emitter 关闭
                        }
                        safeSend(emitter, "done", full);
                        emitter.complete();
                        triggerLongTermMemoryIngestion(streamUserId, sid, userInput);
                        triggerShortTermMaintenance(streamUserSnapshot, streamUserId, sid);
                    } finally {
                        UserContextHolder.clear();
                    }
                }
```

- [ ] **Step 4：重写 `buildMessages` 签名与短期记忆加载**

把原 `private List<Message> buildMessages(String sid, List<ChatMessageDTO> historyDtos, String userInput) { ... }` 整体替换为:

```java
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
    private List<Message> buildMessages(String sid, Long userId, String userInput) {
        List<Message> messages = new ArrayList<>();
        StringBuilder systemBlock = new StringBuilder();
        systemBlock.append(sessionClockAnchorLine());

        String instruction = properties.getInstruction() == null ? "" : properties.getInstruction().trim();
        if (!instruction.isBlank()) {
            systemBlock.append("\n\n---\n");
            systemBlock.append(instruction);
        }

        // 三级读穿；冷会话才会调用 L3 加载器（运行在当前 Servlet 线程，UserContext 可用）。
        ShortTermMemorySnapshot snapshot = shortTermMemoryService.loadForTurn(
                userId, sid, () -> memoryStore.load(sid));
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

        List<ChatMessageDTO> contextMessages = snapshot.recentMessages() == null
                ? List.of() : snapshot.recentMessages();
        log.debug("[ChatService] 组装模型上下文 sid={}, contextWindowSize={}", sid, contextMessages.size());
        appendReplayableMessages(messages, contextMessages);
        return messages;
    }
```

- [ ] **Step 5：用 `triggerShortTermMaintenance` 替换 `triggerMemoryCompressionIfNeeded`**

删除整个 `triggerMemoryCompressionIfNeeded(...)` 方法,新增:

```java
    /**
     * 对话结束后的短期记忆异步维护：读 L3 全量 → 达阈值则压缩更新 L1 摘要 → 把 L1 刷入 L2 快照。
     * <p>运行在异步线程，必须回放 {@link UserContext}：{@link com.yuyu.fishagent.chat.history.RustFsChatMemoryStore#load}
     * 会校验归属并从 ThreadLocal 取 userId。</p>
     *
     * @param userSnapshot 进入流式前快照的用户上下文（可能为 null）
     * @param userId       当前用户 ID（供 L2 文件实现分区）
     * @param sid          会话 ID
     */
    private void triggerShortTermMaintenance(UserContext userSnapshot, Long userId, String sid) {
        CompletableFuture.runAsync(() -> {
            if (userSnapshot != null) {
                UserContextHolder.set(userSnapshot);
            }
            try {
                List<ChatMessageDTO> full = memoryStore.load(sid); // L3 读取移到异步，不阻塞首字
                if (full.size() >= memoryProperties.getSummaryTriggerThreshold()) {
                    log.debug("[ChatService] 触发异步记忆压缩 sid={}, historySize={}, threshold={}",
                            sid, full.size(), memoryProperties.getSummaryTriggerThreshold());
                    memoryCompressionService.compress(new MemoryCompressionRequest(sid, full)); // 更新 L1 摘要+窗口
                }
                shortTermMemoryService.refreshSnapshotFromL1(userId, sid); // L1 → L2 快照
            } catch (Exception e) {
                log.warn("[ChatService] 短期记忆维护失败 sid={}: {}", sid, e.getMessage());
            } finally {
                UserContextHolder.clear();
            }
        });
    }
```

- [ ] **Step 6：`deleteSession` 联动清理 L1+L2**

把原:

```java
    public void deleteSession(String sessionId) {
        memoryStore.clear(sessionId);
    }
```

替换为:

```java
    public void deleteSession(String sessionId) {
        Long uid = UserContextHolder.currentUserIdOrNull();
        memoryStore.clear(sessionId); // 内含归属校验；先于短期记忆清理
        shortTermMemoryService.clear(uid, sessionId);
    }
```

- [ ] **Step 7：删除无用的私有方法 `recentMessages`**

`buildMessages` 改用协调器窗口后,ChatService 内的 `private List<ChatMessageDTO> recentMessages(List<ChatMessageDTO> chatHistory, int windowSize)`(行约 344–350)不再被调用,删除该方法,避免未用告警。
若 IDE 提示 `java.util.Objects` 等 import 仍被其它方法使用则保留;仅删确实不再使用的。

- [ ] **Step 8：编译 + 全量测试**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

Run: `mvn -q test`
Expected: 全部 PASS(含既有 `MemoryCompressionServiceTest`、`BackendPackageRefactorTest` 等)。
若 `BackendPackageRefactorTest` 对包结构有断言,确认新增类位于 `com.yuyu.fishagent.memory.shortterm` 包内(与现有短期记忆类同包),通常无需改该测试;如失败,阅读其断言再决定。

- [ ] **Step 9：提交**

```bash
git add src/main/java/com/yuyu/fishagent/chat/ChatService.java
git commit -m "refactor(chat): wire tiered short-term memory (L1 hot read, sync L3+L1, async L2/compress)"
```

---

## Task 6：终检与回归

- [ ] **Step 1：全量构建**

Run: `mvn -q clean test`
Expected: BUILD SUCCESS,无失败用例。

- [ ] **Step 2：人工核对清单(无对应自动化测试的点)**
  - 启动应用(`mvn spring-boot:run` 或 IDE),用同一会话连续对话 ≥ 阈值轮次:
    - 前几轮:Redis 此前为空,首轮冷路径(L3 仅本轮,走窗口降级,summary 空),之后每轮热路径读 L1 命中。
    - 观察日志:热路径不应出现每轮 `memoryStore.load` 的全量读(全量读只应在异步维护任务出现)。
  - 达阈值后:异步日志出现"触发异步记忆压缩",随后 `fish-chat` 桶出现 `{sid}.stm.json`。
  - 手动删除 Redis 中 `fish:memory:short:{sid}:*` 两个 key,再对话一轮:应从 L2 `{sid}.stm.json` 回源并回填 L1,摘要不丢。
  - 删除会话:`fish-chat` 桶内 `{sid}.json` 与 `{sid}.stm.json` 均被删除,Redis key 清空。
  - 前端"查看会话历史"(`GET /api/chat/sessions/{sid}`)仍显示全量(走 L3,不受 L1 窗口裁剪影响)。

- [ ] **Step 3:(可选)更新文档**
  - 在 `document/模块要点/模块3-分层记忆与RAG.md` 增补 L2 快照层与三级读穿/写穿说明(若需要)。本步骤不阻塞功能交付。

---

## Self-Review(计划对照 spec 的自检)

- **L1 每轮主存**:Task 5 Step 3 同步 `appendTurnToL1` 覆盖;读路径 Task 5 Step 4 优先 L1。✅
- **Redis miss 回源对象存储**:Task 4 `loadForTurn` L2 分支 + 回填 L1。✅
- **对话中用 Redis 短期记忆,用户可显示全部**:热路径读 L1;前端历史接口走 L3(`getHistory`/`listSessions` 未改动)。✅
- **结束后异步落盘短期记忆 + 对话记录**:对话记录 L3 同步(决策 sync);短期记忆 L1 窗口同步(正确性修正)、L2 快照 + 压缩异步(决策 sync 的"快照+压缩走异步")。✅
- **冷会话同步重算(决策 recompute)**:Task 4 `loadForTurn` 冷分支 + `snapshot.recomputeOnCold` 开关。✅
- **对象存储快照(决策 a)**:Task 3 双实现 + Task 4 写穿。✅
- **占位符扫描**:无 TBD/TODO,所有步骤含完整代码与命令。✅
- **类型/签名一致性**:`ShortTermSnapshotStore.load/save/clear(Long,String,...)`、`ShortTermMemoryService(l1,l2,compression,properties)`、`buildMessages(String,Long,String)`、`triggerShortTermMaintenance(UserContext,Long,String)` 在各任务间一致。✅
- **循环依赖**:`ShortTermMemoryService` → `MemoryCompressionService`(单向);`MemoryCompressionService` 不依赖协调器。✅

## 风险与注意

- **冷路径同步重算阻塞首字 1–5s**:仅在 L1+L2 均未命中且历史 ≥ 阈值时发生(主要是上线后老会话首访)。可用 `fish.memory.snapshot.recompute-on-cold=false` 关闭,降级为窗口(摘要空)。
- **跨轮异步竞态**:会话锁在 `emitter.complete()` 释放,异步维护可能与下一轮重叠;但下一轮热路径读的是 L1,而 L1 窗口已在本轮**同步** `appendTurnToL1` 更新,故下一轮可见本轮消息。L2/压缩为"最后写入者获胜",均为有效摘要,可接受。
- **ObjectMapper**:L2 两个实现必须注入 Spring 容器的 `ObjectMapper`(已注册 ParameterNamesModule),不要用 `new ObjectMapper()`,否则 record 反序列化可能失败。
- **`ChatMessageDTO.equals`**:仅影响测试断言写法,见 Task 4 Step 1 注释,勿为通过测试给生产类强加 `equals`。
- **`AgentProperties.getHistoryDir()`**:`FileShortTermSnapshotStore` 依赖它;若不存在应停下确认,勿擅自加配置。

---

## 实现记录与计划调整（Codex 2026-06-01）

- **未按任务逐个提交 commit**：当前工作区在实现前已有用户侧删除项和 `temp/` 未跟踪内容，且用户本次要求是实现计划而非提交历史；因此本次只做代码修改，不执行计划中的逐任务 `git commit`。
- **分支名调整**：计划外先从 `main` 切到 `codex-short-term-tiered-memory`。`codex/...` 层级分支在当前仓库创建失败，改用无斜杠分支名。
- **防御性增强**：`ShortTermMemoryService` 对 L1/L2 返回 `null` 或 `recentMessages=null` 做空快照兜底；RustFS/File L2 实现也对空载荷写入/读取做降级处理。这不改变目标行为，只降低存储实现异常或历史脏数据导致热路径 NPE 的风险。
- **验证阻塞**：本机 `java -version` / `mvn -version` 均指向 JDK 17，但 `pom.xml` 要求 Java 21。`mvn -q -DskipTests compile` 与 `mvn -q -Dtest=ShortTermMemoryServiceTest test` 均因 `不支持发行版本 21` 失败；临时降到 release 17 又会卡在项目已有 Java 21 语法（如 `case RateLimitResult.Allowed()`）。需要在 JDK 21 环境重新运行正式编译与测试。
- **验证更新**：用户确认 Java 21 位于 `D:\Code\jdks\ms-21.0.9` 后，已用临时 `JAVA_HOME` 重跑：
  - `mvn -q -DskipTests compile`：通过。
  - `mvn -q -Dtest=ShortTermMemoryServiceTest test`：通过。
  - `mvn -q clean test`：通过。该命令需临时设置 `DASHSCOPE_API_KEY=dummy` / `SPRING_AI_DASHSCOPE_API_KEY=dummy`，仅用于让 SpringBoot 上下文测试完成 DashScope Embedding Bean 初始化，测试过程未实际调用 embedding API。
