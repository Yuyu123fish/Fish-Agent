package com.yuyu.fishagent.common.redis;

/**
 * Redis Key 命名空间集中字典。根前缀 {@code fish:} 为品牌锁，单一来源。
 *
 * <pre>
 * fish:session:{token}                  TTL 86400s   dim=userId   会话
 * fish:memory:short:{sid}:snapshot      TTL 30d      dim=session  短期记忆快照
 * fish:memory:short:{sid}:messages      TTL 30d      dim=session  短期记忆消息
 * fish:memory:short:{sid}:summary       TTL 30d      dim=session  短期记忆摘要
 * fish:memory:agent-state:{sid}         TTL 30d      dim=session  Agent 状态
 * fish:cache:card:{name}:{userId}:{id}  TTL 5~10min  dim=userId   卡片缓存
 * fish:ratelimit:token:{userId}         TTL 120s     dim=userId   令牌桶
 * fish:ratelimit:sse:{userId}           TTL 300s     dim=userId   SSE 并发计数
 * fish:mutex:session:{sid}              TTL 锁周期   dim=session  会话互斥锁
 * fish:doc:ingest                       Stream       -            文档摄入流
 * </pre>
 */
public final class RedisKeys {

    /** 根前缀，保持不可配置，避免多处拼写漂移。 */
    public static final String ROOT = "fish";

    public static final String SESSION = ROOT + ":session";
    public static final String MEMORY = ROOT + ":memory";
    public static final String CACHE_CARD = ROOT + ":cache:card:";
    public static final String RATELIMIT = ROOT + ":ratelimit:";
    public static final String MUTEX_SESSION = ROOT + ":mutex:session:";
    public static final String DOC_STREAM = ROOT + ":doc:ingest";

    private RedisKeys() {
    }

    public static String session(String token) {
        return SESSION + ":" + token;
    }

    public static String memoryShortSnapshot(String sessionId) {
        return MEMORY + ":short:" + sessionId + ":snapshot";
    }

    public static String memoryShortMessages(String sessionId) {
        return MEMORY + ":short:" + sessionId + ":messages";
    }

    public static String memoryShortSummary(String sessionId) {
        return MEMORY + ":short:" + sessionId + ":summary";
    }

    public static String memoryAgentState(String sessionId) {
        return MEMORY + ":agent-state:" + sessionId;
    }

    public static String rateToken(long userId) {
        return RATELIMIT + "token:" + userId;
    }

    public static String rateSse(long userId) {
        return RATELIMIT + "sse:" + userId;
    }

    public static String mutexSession(String sessionId) {
        return MUTEX_SESSION + sessionId;
    }
}
