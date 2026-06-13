package com.yuyu.fishagent.common.cache;

import org.junit.jupiter.api.Test;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedisCacheConfigTest {

    @Test
    void shouldExposeCardCacheTtlConfiguration() {
        Map<String, RedisCacheConfiguration> configs = RedisCacheConfig.cardCacheConfigurations();

        assertThat(configs).containsOnlyKeys(
                CacheConstants.CARD_DETAIL,
                CacheConstants.CARD_STATS,
                CacheConstants.CARD_RELATIONS
        );
        assertThat(configs.get(CacheConstants.CARD_DETAIL).getTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(configs.get(CacheConstants.CARD_STATS).getTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(configs.get(CacheConstants.CARD_RELATIONS).getTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void shouldUseUnifiedCardCachePrefix() {
        String computedPrefix = RedisCacheConfig.defaultCacheConfiguration()
                .getKeyPrefixFor(CacheConstants.CARD_DETAIL);

        assertThat(computedPrefix).isEqualTo(CacheConstants.KEY_PREFIX + CacheConstants.CARD_DETAIL + ":");
        assertThat(computedPrefix).doesNotContain("::");
    }

    @Test
    void shouldProvideCacheErrorHandlerForRedisFallback() {
        CacheErrorHandler errorHandler = new RedisCacheConfig().errorHandler();

        assertThat(errorHandler).isNotNull();
    }
}
