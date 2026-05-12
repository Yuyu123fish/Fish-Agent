package com.yuyu.fishagent.rag.pipeline.expand;

import com.yuyu.fishagent.rag.config.RagProperties;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 多查询扩展（RAG 第二类）：将单条检索串拆成多条子查询，供并发 ES 召回。
 * <p>无单独「是否开启扩展」配置：只要 {@code fish.rag.enabled=true}，编排层始终调用 {@link SubQueryExpand#expand}。</p>
 * <p>与 {@link com.yuyu.fishagent.rag.pipeline.query.RagQueryRewrite} 无编译依赖；上游可为原文或重写结果。</p>
 */
public final class RagQueryExpand {

    private RagQueryExpand() {
    }

    /** 子查询扩展 SPI：入参为查询重写阶段的单条输出。 */
    @FunctionalInterface
    public interface SubQueryExpander {
        List<String> expand(String rewrittenQuery);
    }

    /**
     * 基于 {@link BreakIterator}：首条为整句，后续为词界片段。
     * <p>中文（两个及以上汉字）用 {@link Locale#CHINESE}，否则 {@link Locale#ROOT}。</p>
     */
    public static final class BreakIteratorExpander implements SubQueryExpander {

        private final RagProperties ragProperties;

        public BreakIteratorExpander(RagProperties ragProperties) {
            this.ragProperties = ragProperties;
        }

        @Override
        public List<String> expand(String rewrittenQuery) {
            if (rewrittenQuery == null || rewrittenQuery.isBlank()) {
                return List.of();
            }
            String trimmed = rewrittenQuery.trim();
            int max = Math.max(1, ragProperties.getRecall().getMaxSubQueries());
            int minChars = Math.max(1, ragProperties.getRecall().getMinTokenChars());

            Set<String> unique = new LinkedHashSet<>();
            unique.add(trimmed);

            BreakIterator it = BreakIterator.getWordInstance(pickLocale(trimmed));
            it.setText(trimmed);
            int start = it.first();
            for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
                if (end <= start) {
                    continue;
                }
                String word = trimmed.substring(start, end).trim();
                if (word.length() < minChars || word.equals(trimmed)) {
                    continue;
                }
                if (!isSubstantiveToken(word)) {
                    continue;
                }
                unique.add(word);
                if (unique.size() >= max) {
                    break;
                }
            }
            return new ArrayList<>(unique);
        }

        private static Locale pickLocale(String text) {
            int han = 0;
            for (int i = 0; i < text.length(); i++) {
                if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                    han++;
                    if (han >= 2) {
                        return Locale.CHINESE;
                    }
                }
            }
            return Locale.ROOT;
        }

        private static boolean isSubstantiveToken(String s) {
            return s.codePoints().anyMatch(cp ->
                    Character.isLetterOrDigit(cp) || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        }
    }
}
