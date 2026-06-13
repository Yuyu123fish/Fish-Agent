package com.yuyu.fishagent.common.cache;

import com.yuyu.fishagent.common.redis.RedisKeys;

/**
 * Spring Cache 统一常量。
 * <p>缓存名、Redis key 前缀和 SpEL key 都集中放在这里，避免业务服务里散落魔法字符串。
 * 后续如果要按用户精确驱逐或扩展新的卡片缓存，只需要从这里统一调整命名规则。</p>
 */
public final class CacheConstants {

    private CacheConstants() {
    }

    /** Redis 实际 key 前缀：最终形态为 fish:cache:card:{cacheName}:{业务key}。 */
    public static final String KEY_PREFIX = RedisKeys.CACHE_CARD;

    /** 卡片详情缓存：key = userId:cardId。 */
    public static final String CARD_DETAIL = "card-detail";

    /** 卡片统计缓存：key = userId。 */
    public static final String CARD_STATS = "card-stats";

    /** 图谱关联缓存：key = userId。 */
    public static final String CARD_RELATIONS = "card-relations";

    /** 当前登录用户 key，保证不同用户之间不会互相命中缓存。 */
    public static final String KEY_CURRENT_USER =
            "T(com.yuyu.fishagent.auth.context.UserContextHolder).currentUserIdOrNull()";

    /** 当前用户 + 卡片 ID key，避免 @Cacheable 命中时跳过方法体权限校验造成越权读。 */
    public static final String KEY_CURRENT_USER_CARD_ID = KEY_CURRENT_USER + " + ':' + #id";
}
