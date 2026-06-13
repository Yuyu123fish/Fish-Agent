package com.yuyu.fishagent.common.redis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisKeyInspectionControllerTest {

    @Test
    void shouldGroupKeysByStableNamespace() {
        assertThat(RedisKeyInspectionController.namespaceOf("fish:cache:card:card-detail:1:2"))
                .isEqualTo("fish:cache:card");
        assertThat(RedisKeyInspectionController.namespaceOf("fish:memory:short:sid:snapshot"))
                .isEqualTo("fish:memory:short");
        assertThat(RedisKeyInspectionController.namespaceOf("fish:ratelimit:token:7"))
                .isEqualTo("fish:ratelimit:token");
        assertThat(RedisKeyInspectionController.namespaceOf("other:key"))
                .isEqualTo("other:key");
    }
}
