package com.yuyu.fishagent.common.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Redis 作为 Spring Cache 后端的统一配置。
 * <p>这里只定义应用数据缓存，不影响会话、限流、短期记忆等已有 Redis key 空间。</p>
 */
@Slf4j
@Configuration
@EnableCaching
public class RedisCacheConfig implements CachingConfigurer {

    private static final Duration CARD_DETAIL_TTL = Duration.ofMinutes(10);
    private static final Duration CARD_STATS_TTL = Duration.ofMinutes(10);
    private static final Duration CARD_RELATIONS_TTL = Duration.ofMinutes(5);

    /**
     * RedisCacheManager：按缓存名设置 TTL，并使用 JSON 序列化 VO/record。
     * <p>{@code transactionAware()} 会把事务内的驱逐动作延后到事务提交后执行，降低写事务回滚后误清缓存的概率。</p>
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultCacheConfiguration())
                .withInitialCacheConfigurations(cardCacheConfigurations())
                .transactionAware()
                .build();
    }

    /**
     * 默认配置抽出来便于单测直接校验，也方便后续新增缓存复用统一序列化策略。
     */
    public static RedisCacheConfiguration defaultCacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith(CacheConstants.KEY_PREFIX)
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonValueSerializer()))
                .disableCachingNullValues()
                .entryTtl(CARD_DETAIL_TTL);
    }

    /**
     * 卡片缓存 TTL 配置：详情/统计偏稳定，关联图谱稍短，避免关系编辑后长期保留旧边。
     */
    public static Map<String, RedisCacheConfiguration> cardCacheConfigurations() {
        RedisCacheConfiguration base = defaultCacheConfiguration();
        return Map.of(
                CacheConstants.CARD_DETAIL, base.entryTtl(CARD_DETAIL_TTL),
                CacheConstants.CARD_STATS, base.entryTtl(CARD_STATS_TTL),
                CacheConstants.CARD_RELATIONS, base.entryTtl(CARD_RELATIONS_TTL)
        );
    }

    /**
     * 缓存错误降级策略。
     * <p>Redis 短暂不可用时，缓存读写/驱逐失败不应阻断卡片业务主流程；读失败会继续执行原方法查 DB，
     * 写失败最多导致下一次缓存未命中或短时间读到旧缓存，风险低于直接让接口失败。</p>
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("[RedisCache] 读取缓存失败 cache={}, key={}: {}",
                        cacheName(cache), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("[RedisCache] 写入缓存失败 cache={}, key={}: {}",
                        cacheName(cache), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("[RedisCache] 驱逐缓存失败 cache={}, key={}: {}",
                        cacheName(cache), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("[RedisCache] 清空缓存失败 cache={}: {}", cacheName(cache), exception.getMessage());
            }
        };
    }

    private static GenericJackson2JsonRedisSerializer jsonValueSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Spring Cache 以 Object 形式读写缓存值，需要类型信息才能把 JSON 还原为具体 VO/record。
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return new GenericJackson2JsonRedisSerializer(mapper);
    }

    private static String cacheName(Cache cache) {
        return cache == null ? "unknown" : cache.getName();
    }
}
