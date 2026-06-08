package com.yuyu.fishagent.common.cache;

import com.yuyu.fishagent.card.service.KnowledgeCardService;
import com.yuyu.fishagent.card.service.CardExtractService;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCardCacheAnnotationTest {

    private static final Set<String> CARD_CACHES = Set.of(
            CacheConstants.CARD_DETAIL,
            CacheConstants.CARD_STATS,
            CacheConstants.CARD_RELATIONS
    );

    @Test
    void shouldCacheCardReadMethodsWithUserScopedKeys() throws NoSuchMethodException {
        assertCacheable(method("stats"), CacheConstants.CARD_STATS, CacheConstants.KEY_CURRENT_USER);
        assertCacheable(method("allRelations"), CacheConstants.CARD_RELATIONS, CacheConstants.KEY_CURRENT_USER);
        assertCacheable(method("detail", Long.class), CacheConstants.CARD_DETAIL, CacheConstants.KEY_CURRENT_USER_CARD_ID);
    }

    @Test
    void shouldEvictAllCardCachesForWriteMethods() throws NoSuchMethodException {
        List<Method> writeMethods = List.of(
                method("create", com.yuyu.fishagent.card.dto.CardCreateRequest.class),
                method("update", Long.class, com.yuyu.fishagent.card.dto.CardUpdateRequest.class),
                method("delete", Long.class),
                method("batchConfirm", List.class),
                method("batchReject", List.class),
                method("merge", Long.class, Long.class),
                method("addRelation", Long.class, Long.class, String.class),
                method("deleteRelation", Long.class)
        );

        for (Method writeMethod : writeMethods) {
            Caching caching = AnnotatedElementUtils.findMergedAnnotation(writeMethod, Caching.class);
            assertThat(caching)
                    .as(writeMethod.getName() + " 应统一驱逐卡片缓存")
                    .isNotNull();

            Set<String> evictedCaches = Arrays.stream(caching.evict())
                    .peek(evict -> assertThat(evict.allEntries()).as(writeMethod.getName()).isTrue())
                    .flatMap(evict -> Arrays.stream(evict.cacheNames()))
                    .collect(Collectors.toSet());

            assertThat(evictedCaches).as(writeMethod.getName()).isEqualTo(CARD_CACHES);
        }
    }

    @Test
    void shouldEvictAllCardCachesWhenExtractingCardsFromChat() throws NoSuchMethodException {
        Method extractMethod = CardExtractService.class.getMethod("extractFromSession", String.class, Long.class);

        assertEvictsAllCardCaches(extractMethod);
    }

    private static void assertCacheable(Method method, String cacheName, String key) {
        Cacheable cacheable = AnnotatedElementUtils.findMergedAnnotation(method, Cacheable.class);
        assertThat(cacheable).as(method.getName() + " 应启用缓存").isNotNull();
        assertThat(cacheable.cacheNames()).containsExactly(cacheName);
        assertThat(cacheable.key()).isEqualTo(key);
    }

    private static void assertEvictsAllCardCaches(Method writeMethod) {
        Caching caching = AnnotatedElementUtils.findMergedAnnotation(writeMethod, Caching.class);
        assertThat(caching)
                .as(writeMethod.getName() + " 应统一驱逐卡片缓存")
                .isNotNull();

        Set<String> evictedCaches = Arrays.stream(caching.evict())
                .peek(evict -> assertThat(evict.allEntries()).as(writeMethod.getName()).isTrue())
                .flatMap(evict -> Arrays.stream(evict.cacheNames()))
                .collect(Collectors.toSet());

        assertThat(evictedCaches).as(writeMethod.getName()).isEqualTo(CARD_CACHES);
    }

    private static Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return KnowledgeCardService.class.getMethod(name, parameterTypes);
    }
}
