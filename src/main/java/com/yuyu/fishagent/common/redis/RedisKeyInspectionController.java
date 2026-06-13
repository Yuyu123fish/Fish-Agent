package com.yuyu.fishagent.common.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Redis key 巡检端点，仅 dev profile 注册。
 * <p>使用 SCAN 做增量遍历，只返回 key 名和命名空间计数，不读取 value，避免泄露数据或阻塞 Redis。</p>
 */
@RestController
@RequestMapping("/admin/redis")
@Profile("dev")
@RequiredArgsConstructor
public class RedisKeyInspectionController {

    private static final int MAX_SAMPLE_LIMIT = 1_000;

    private final StringRedisTemplate redis;

    @GetMapping("/keys")
    public Map<String, Object> inspect(@RequestParam(defaultValue = "fish:*") String pattern,
                                       @RequestParam(defaultValue = "200") int sampleLimit) {
        int limit = Math.max(0, Math.min(sampleLimit, MAX_SAMPLE_LIMIT));
        Map<String, Long> counts = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();

        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                counts.merge(namespaceOf(key), 1L, Long::sum);
                if (samples.size() < limit) {
                    samples.add(key);
                }
            }
        }
        return Map.of("pattern", pattern, "namespaces", counts, "sampleKeys", samples);
    }

    /**
     * 提取稳定命名空间用于巡检聚合。
     * <p>保留前三段通常能区分 domain，例如 {@code fish:cache:card}、
     * {@code fish:memory:short}、{@code fish:ratelimit:token}。</p>
     */
    static String namespaceOf(String key) {
        if (key == null || key.isBlank()) {
            return "unknown";
        }
        String[] parts = key.split(":");
        if (parts.length <= 3 || !"fish".equals(parts[0])) {
            return key;
        }
        int size = Math.min(3, parts.length);
        return String.join(":", java.util.Arrays.copyOf(parts, size));
    }
}
