package com.yuyu.fishagent.rag.pipeline.recall;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 长期记忆时效标签格式化。
 *
 * <p>标签仅用于内部 RAG 注入，帮助模型在冲突事实中倾向更新信息；输出层仍要求模型不要向用户暴露“记忆”元话术。</p>
 */
public final class MemoryAgeLabel {

    private MemoryAgeLabel() {
    }

    public static String format(Long createdAt, Clock clock) {
        if (createdAt == null || createdAt <= 0) {
            return "时间未知";
        }
        long minutes = Math.max(0, Duration.between(Instant.ofEpochMilli(createdAt), clock.instant()).toMinutes());
        if (minutes < 10) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + "分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "小时前";
        }
        long days = hours / 24;
        if (days < 30) {
            return days + "天前";
        }
        long months = days / 30;
        if (months < 12) {
            return months + "个月前";
        }
        return Math.max(1, months / 12) + "年前";
    }
}
