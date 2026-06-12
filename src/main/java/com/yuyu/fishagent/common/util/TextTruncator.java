package com.yuyu.fishagent.common.util;

/**
 * 文本截断工具：按段落/句子边界智能截断，避免在句子中间截断丢失语义。
 */
public final class TextTruncator {

    private TextTruncator() {
    }

    /**
     * 智能截断：优先在段落边界截断，其次句子边界，最后硬截。
     * 截断后追加省略提示，让模型知道有内容被省略。
     *
     * @param text 原始文本
     * @param maxLen 最大长度
     * @return 截断后的文本
     */
    public static String truncate(String text, int maxLen) {
        if (text == null || maxLen <= 0 || text.length() <= maxLen) {
            return text;
        }

        int cut = findBoundary(text, maxLen, "\n\n");
        if (cut < maxLen * 0.6) {
            cut = findBoundary(text, maxLen, "。");
        }
        if (cut < maxLen * 0.6) {
            cut = findBoundary(text, maxLen, ". ");
        }
        if (cut < maxLen * 0.4) {
            cut = maxLen;
        }

        return text.substring(0, cut)
                + "\n\n[内容已截断，原始长度 " + text.length()
                + " 字符，已省略 " + (text.length() - cut) + " 字符]";
    }

    /**
     * 硬预算截断：返回值长度不超过 {@code maxLen}，适合模型上下文预算治理。
     */
    public static String truncateWithin(String text, int maxLen) {
        if (text == null || maxLen <= 0 || text.length() <= maxLen) {
            return text;
        }
        String suffix = "\n\n[内容已截断，原始长度 " + text.length() + " 字符]";
        if (maxLen <= suffix.length() + 1) {
            return text.substring(0, maxLen);
        }

        int bodyLimit = maxLen - suffix.length();
        int cut = findBoundary(text, bodyLimit, "\n\n");
        if (cut < bodyLimit * 0.6) {
            cut = findBoundary(text, bodyLimit, "。");
        }
        if (cut < bodyLimit * 0.6) {
            cut = findBoundary(text, bodyLimit, ". ");
        }
        if (cut < bodyLimit * 0.4) {
            cut = bodyLimit;
        }
        return text.substring(0, cut) + suffix;
    }

    /**
     * 压缩输入专用：保留 head + tail，中间省略，降低压缩模型 token 消耗。
     *
     * @param text 原始文本
     * @param headLen 保留开头多少字符
     * @param tailLen 保留结尾多少字符
     * @return head + 省略标记 + tail
     */
    public static String headTailCompress(String text, int headLen, int tailLen) {
        if (text == null || headLen < 0 || tailLen < 0 || text.length() <= headLen + tailLen) {
            return text;
        }
        return text.substring(0, headLen)
                + "\n...[省略 " + (text.length() - headLen - tailLen) + " 字符]...\n"
                + text.substring(text.length() - tailLen);
    }

    private static int findBoundary(String text, int maxLen, String delimiter) {
        int idx = text.lastIndexOf(delimiter, maxLen);
        return idx > 0 ? idx + delimiter.length() : -1;
    }
}
