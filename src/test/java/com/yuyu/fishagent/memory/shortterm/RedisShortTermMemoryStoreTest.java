package com.yuyu.fishagent.memory.shortterm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisShortTermMemoryStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadFallsBackToLegacyKeysWhenSnapshotJsonIsCorrupt() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("fish:memory:short:sid:snapshot")).thenReturn("{bad-json");
        when(ops.get("fish:memory:short:sid:summary")).thenReturn("legacy summary");
        when(ops.get("fish:memory:short:sid:messages")).thenReturn(objectMapper.writeValueAsString(
                List.of(ChatMessageDTO.of("user", "legacy message"))));

        RedisShortTermMemoryStore store = new RedisShortTermMemoryStore(
                provider(redis), objectMapper, new MemoryProperties());

        ShortTermMemorySnapshot snapshot = store.load("sid");

        assertThat(snapshot.summary()).contains("legacy summary");
        assertThat(snapshot.recentMessages())
                .extracting(ChatMessageDTO::getContent)
                .containsExactly("legacy message");
    }

    private static ObjectProvider<StringRedisTemplate> provider(StringRedisTemplate redis) {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject(Object... args) {
                return redis;
            }

            @Override
            public StringRedisTemplate getIfAvailable() {
                return redis;
            }

            @Override
            public StringRedisTemplate getIfUnique() {
                return redis;
            }

            @Override
            public StringRedisTemplate getObject() {
                return redis;
            }
        };
    }
}
