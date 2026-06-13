package com.yuyu.fishagent.agent.tool.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LargeResultScratchStoreTest {

    @Test
    void storesAndSearchesLargeResultByTurn() {
        ToolResultProperties properties = new ToolResultProperties();
        properties.setScratchChunkTokens(50);
        properties.setScratchInjectTopK(2);
        LargeResultScratchStore store = newStore(properties);

        LargeResultScratchStore.StoreResult stored = store.store(
                "turn-1",
                "log_query",
                "alpha line\n".repeat(30) + "target error 500 stack\n" + "tail line\n".repeat(30));

        assertThat(stored.stored()).isTrue();
        LargeResultScratchStore.SearchResult result = store.search("turn-1", "error 500");

        assertThat(result.ok()).isTrue();
        assertThat(result.render()).contains("error 500");
    }

    @Test
    void limitsSearchCallsPerTurn() {
        ToolResultProperties properties = new ToolResultProperties();
        properties.setScratchSearchMaxCalls(1);
        LargeResultScratchStore store = newStore(properties);
        store.store("turn-1", "log_query", "target error");

        assertThat(store.search("turn-1", "target").ok()).isTrue();
        assertThat(store.search("turn-1", "target").ok()).isFalse();
    }

    @Test
    void splitsChineseChunksWithinConfiguredTokenBudget() {
        ToolResultProperties properties = new ToolResultProperties();
        properties.setScratchChunkTokens(120);
        LargeResultScratchStore store = newStore(properties);

        LargeResultScratchStore.StoreResult stored = store.store(
                "turn-1", "log_query", "中文错误日志".repeat(300));

        assertThat(stored.previewChunks()).isNotEmpty();
        assertThat(stored.previewChunks())
                .allSatisfy(chunk -> assertThat(chunk.tokens()).isLessThanOrEqualTo(120));
    }

    @Test
    void emptyScratchSearchDoesNotConsumeCallLimit() {
        ToolResultProperties properties = new ToolResultProperties();
        properties.setScratchSearchMaxCalls(1);
        LargeResultScratchStore store = newStore(properties);

        assertThat(store.search("turn-1", "missing").ok()).isTrue();
        store.store("turn-1", "log_query", "target error");

        assertThat(store.search("turn-1", "target").ok()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private LargeResultScratchStore newStore(ToolResultProperties properties) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new LargeResultScratchStore(provider, new ObjectMapper(), properties);
    }
}
