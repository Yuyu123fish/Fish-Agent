package com.yuyu.fishagent.common.redis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisKeysTest {

    @Test
    void shouldBuildKnownRedisKeysFromSingleDictionary() {
        assertThat(RedisKeys.session("token")).isEqualTo("fish:session:token");
        assertThat(RedisKeys.memoryShortSnapshot("sid")).isEqualTo("fish:memory:short:sid:snapshot");
        assertThat(RedisKeys.memoryShortMessages("sid")).isEqualTo("fish:memory:short:sid:messages");
        assertThat(RedisKeys.memoryShortSummary("sid")).isEqualTo("fish:memory:short:sid:summary");
        assertThat(RedisKeys.memoryAgentState("sid")).isEqualTo("fish:memory:agent-state:sid");
        assertThat(RedisKeys.rateToken(7L)).isEqualTo("fish:ratelimit:token:7");
        assertThat(RedisKeys.rateSse(7L)).isEqualTo("fish:ratelimit:sse:7");
        assertThat(RedisKeys.mutexSession("sid")).isEqualTo("fish:mutex:session:sid");
        assertThat(RedisKeys.DOC_STREAM).isEqualTo("fish:doc:ingest");
    }
}
