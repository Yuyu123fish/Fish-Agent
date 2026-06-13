package com.yuyu.fishagent.agent.tool.result;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.common.redis.RedisKeys;
import com.yuyu.fishagent.common.util.TokenEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 单轮大工具结果 scratch store。
 *
 * <p>生产优先使用 Redis，key 绑定 turnId 并设置 TTL；测试或 Redis 不可用时使用进程内兜底，
 * 兜底只为不阻断主链路，跨进程不可见。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LargeResultScratchStore {

    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{IsHan}\\p{L}\\p{N}_]+");

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;
    private final ToolResultProperties properties;
    private final ConcurrentMap<String, List<ScratchChunk>> localStore = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> localCalls = new ConcurrentHashMap<>();

    public StoreResult store(String turnId, String toolName, String result) {
        if (turnId == null || turnId.isBlank() || result == null || result.isBlank()) {
            return StoreResult.disabled();
        }
        String scratchId = UUID.randomUUID().toString();
        List<ScratchChunk> chunks = split(toolName, scratchId, result);
        if (chunks.isEmpty()) {
            return StoreResult.disabled();
        }
        List<ScratchChunk> all = new ArrayList<>(load(turnId));
        all.addAll(chunks);
        save(turnId, all);
        return new StoreResult(scratchId, chunks.size(), chunks);
    }

    public SearchResult search(String turnId, String query) {
        if (turnId == null || turnId.isBlank()) {
            return new SearchResult(false, "ERROR: missing turnId", List.of());
        }
        List<ScratchChunk> chunks = load(turnId);
        if (chunks.isEmpty()) {
            return new SearchResult(true, "scratch is empty for this turn", List.of());
        }
        if (!incrementCall(turnId)) {
            return new SearchResult(false, "ERROR: search_large_result call limit exceeded", List.of());
        }
        Set<String> terms = terms(query);
        List<ScratchChunk> hits = chunks.stream()
                .sorted(Comparator.comparingInt((ScratchChunk c) -> score(c, terms)).reversed()
                        .thenComparingInt(ScratchChunk::chunkIndex))
                .limit(Math.max(1, properties.getScratchInjectTopK()))
                .toList();
        return new SearchResult(true, "ok", hits);
    }

    public void clear(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            redis.delete(List.of(RedisKeys.scratch(turnId), RedisKeys.scratchCalls(turnId)));
        }
        localStore.remove(turnId);
        localCalls.remove(turnId);
    }

    private List<ScratchChunk> split(String toolName, String scratchId, String result) {
        int maxChars = Math.max(120, initialChunkCharBudget(result));
        List<ScratchChunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 1;
        while (start < result.length()) {
            int end = Math.min(result.length(), start + maxChars);
            if (end < result.length()) {
                int boundary = Math.max(result.lastIndexOf('\n', end), result.lastIndexOf('。', end));
                if (boundary > start + maxChars / 2) {
                    end = boundary + 1;
                }
            }
            String text = result.substring(start, end);
            while (TokenEstimator.estimate(text) > Math.max(1, properties.getScratchChunkTokens())
                    && end > start + 80) {
                end = start + Math.max(80, (int) ((end - start) * 0.85));
                text = result.substring(start, end);
            }
            chunks.add(new ScratchChunk(scratchId, toolName, index++, text, TokenEstimator.estimate(text)));
            start = end;
        }
        return chunks;
    }

    private int initialChunkCharBudget(String result) {
        int tokenBudget = Math.max(1, properties.getScratchChunkTokens());
        int sampleChars = Math.min(result.length(), 2_000);
        int sampleTokens = Math.max(1, TokenEstimator.estimate(result.substring(0, sampleChars)));
        double charsPerToken = Math.max(1.0, Math.min(4.0, sampleChars / (double) sampleTokens));
        return (int) Math.floor(tokenBudget * charsPerToken);
    }

    private List<ScratchChunk> load(String turnId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return localStore.getOrDefault(turnId, List.of());
        }
        try {
            String raw = redis.opsForValue().get(RedisKeys.scratch(turnId));
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("[LargeResultScratchStore] 读取 scratch 失败 turnId={}: {}", turnId, e.getMessage());
            return localStore.getOrDefault(turnId, List.of());
        }
    }

    private void save(String turnId, List<ScratchChunk> chunks) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            localStore.put(turnId, List.copyOf(chunks));
            return;
        }
        try {
            Duration ttl = properties.getScratchTtl() == null ? Duration.ofMinutes(30) : properties.getScratchTtl();
            redis.opsForValue().set(RedisKeys.scratch(turnId), objectMapper.writeValueAsString(chunks), ttl);
            redis.expire(RedisKeys.scratchCalls(turnId), ttl);
        } catch (Exception e) {
            log.warn("[LargeResultScratchStore] 写入 scratch 失败 turnId={}: {}", turnId, e.getMessage());
            localStore.put(turnId, List.copyOf(chunks));
        }
    }

    private boolean incrementCall(String turnId) {
        int maxCalls = Math.max(1, properties.getScratchSearchMaxCalls());
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            int calls = localCalls.merge(turnId, 1, Integer::sum);
            return calls <= maxCalls;
        }
        try {
            Long calls = redis.opsForValue().increment(RedisKeys.scratchCalls(turnId));
            redis.expire(RedisKeys.scratchCalls(turnId), properties.getScratchTtl());
            return calls == null || calls <= maxCalls;
        } catch (Exception e) {
            int calls = localCalls.merge(turnId, 1, Integer::sum);
            return calls <= maxCalls;
        }
    }

    private int score(ScratchChunk chunk, Set<String> terms) {
        if (terms.isEmpty()) {
            return 0;
        }
        String lower = chunk.content().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (lower.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private Set<String> terms(String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        return WORD_SPLIT.splitAsStream(query.toLowerCase(Locale.ROOT))
                .filter(s -> s.length() >= 2)
                .collect(Collectors.toSet());
    }

    public record ScratchChunk(String scratchId, String toolName, int chunkIndex, String content, int tokens) {
    }

    public record StoreResult(String scratchId, int chunkCount, List<ScratchChunk> previewChunks) {
        static StoreResult disabled() {
            return new StoreResult(null, 0, List.of());
        }

        public boolean stored() {
            return scratchId != null && chunkCount > 0;
        }
    }

    public record SearchResult(boolean ok, String message, List<ScratchChunk> hits) {
        public String render() {
            if (!ok) {
                return message;
            }
            if (hits == null || hits.isEmpty()) {
                return message;
            }
            StringBuilder sb = new StringBuilder("scratch search result:\n");
            for (ScratchChunk hit : hits) {
                sb.append("\n[")
                        .append(hit.toolName()).append(" #").append(hit.chunkIndex())
                        .append(" scratchId=").append(hit.scratchId()).append("]\n")
                        .append(hit.content()).append('\n');
            }
            return sb.toString().trim();
        }
    }
}
