package com.yuyu.fishagent.agent.memory.longterm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 写入 ES 前对模型抽取的「事实」做最后一道过滤，避免把助手/产品说明写进用户长期记忆索引。
 */
public final class LongTermMemoryFactSanitizer {

    private LongTermMemoryFactSanitizer() {
    }

    private static final Pattern PRODUCT_CAPABILITY =
            Pattern.compile(".*(fish-agent|fish\\s*agent).*(具备|具有|拥有|支持|包括|集成了?|是一个).*",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 勿含裸 {@code Agent}，否则会匹配到单词 Fish-Agent 中的子串。 */
    private static final Pattern TECH_STACK =
            Pattern.compile(".*(RAG|ReAct|react|工具调用|记忆|推理|嵌入|向量|检索|智能体).*",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * 过滤不应进入用户长期向量索引的条目。
     *
     * @param facts 模型解析出的事实
     * @return 可写入索引的子集
     */
    public static List<String> forIndexing(List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(facts.size());
        for (String fact : facts) {
            if (fact == null || fact.isBlank()) {
                continue;
            }
            if (shouldDropAgentProductBlurb(fact.trim())) {
                continue;
            }
            out.add(fact);
        }
        return out;
    }

    /**
     * 典型误存：「Fish-Agent 是一个具备 RAG、ReAct…」——属于产品说明，不是关于用户本人的稳定事实。
     * <p>若同时明显锚定到用户（「用户是开发者」「我喜欢用…」）则保留。</p>
     */
    static boolean shouldDropAgentProductBlurb(String fact) {
        String compact = fact.replace('\n', ' ').trim();
        if (compact.isEmpty()) {
            return true;
        }
        String lower = compact.toLowerCase(Locale.ROOT);
        boolean namesProduct = lower.contains("fish-agent") || lower.contains("fish agent");
        if (!namesProduct) {
            return false;
        }
        boolean userAnchored = compact.contains("用户是") || compact.contains("用户名叫") || compact.contains("用户喜欢")
                || compact.contains("用户希望") || compact.contains("用户不想")
                || compact.startsWith("用户")
                || compact.contains("我叫") || compact.contains("我是") || compact.contains("我喜欢")
                || compact.contains("我不喜欢") || compact.contains("我在做") || compact.contains("我在开发");
        if (userAnchored) {
            return false;
        }
        boolean looksLikeCapabilityBlurb = PRODUCT_CAPABILITY.matcher(compact).matches()
                || (TECH_STACK.matcher(compact).matches() && (compact.contains("具备") || compact.contains("是一个")
                || compact.contains("支持") || compact.contains("包括") || compact.contains("集成了")));
        return looksLikeCapabilityBlurb;
    }
}
