package com.yuyu.fishagent.rag.pipeline.expand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.common.trace.MdcAsync;
import com.yuyu.fishagent.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

        /**
         * 带对话上下文的扩展。默认忽略上下文，委托给单参数版本。
         * 只有 {@link LlmQueryDecomposer} 需要重写此方法。
         */
        default List<String> expand(String rewrittenQuery, String contextHint) {
            return expand(rewrittenQuery);
        }
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

    /** 恒等扩展：单条 trim 后原句，用于禁用扩展或显式 IDENTITY 策略。 */
    public static final class IdentityExpander implements SubQueryExpander {
        @Override
        public List<String> expand(String rewrittenQuery) {
            if (rewrittenQuery == null || rewrittenQuery.isBlank()) {
                return List.of();
            }
            return List.of(rewrittenQuery.trim());
        }
    }

    /**
     * LLM 语义查询分解：生成少量完整检索句；超时、解析失败或模型不可用时由调用链降级。
     */
    @Slf4j
    public static final class LlmQueryDecomposer implements SubQueryExpander {

        private final ChatModel chatModel;
        private final RagProperties ragProperties;
        private final ObjectMapper objectMapper;

        public LlmQueryDecomposer(ChatModel chatModel, RagProperties ragProperties, ObjectMapper objectMapper) {
            this.chatModel = chatModel;
            this.ragProperties = ragProperties;
            this.objectMapper = objectMapper;
        }

        @Override
        public List<String> expand(String rewrittenQuery, String contextHint) {
            if (rewrittenQuery == null || rewrittenQuery.isBlank()) {
                return List.of();
            }
            String original = rewrittenQuery.trim();
            RagProperties.Expand cfg = ragProperties.getExpand();
            int maxQueries = Math.min(Math.max(1, cfg.getMaxQueries()), Math.max(1, ragProperties.getRecall().getMaxSubQueries()));
            try {
                CompletableFuture<String> future = MdcAsync.mdcSupplyAsync(() -> {
                    Prompt prompt = new Prompt(
                            new SystemMessage(RagQueryDecomposePrompt.SYSTEM_INSTRUCTION),
                            new UserMessage(
                                    contextHint != null && !contextHint.isBlank()
                                        ? RagQueryDecomposePrompt.userSegmentWithContext(original, contextHint)
                                        : RagQueryDecomposePrompt.userSegment(original)));
                    return chatModel.call(prompt).getResult().getOutput().getText();
                });
                String raw = future.get(Math.max(1, cfg.getTimeoutMs()), TimeUnit.MILLISECONDS);
                List<String> result = parseAndValidate(
                        objectMapper, raw, original, cfg.getMinQueryChars(), cfg.getMaxQueryChars(), maxQueries);
                log.debug("[LlmQueryDecomposer] 原句拆解为 {} 条子查询（含上下文）", result.size());
                return result;
            } catch (Exception e) {
                log.warn("[LlmQueryDecomposer] 查询分解失败/超时，降级为单条原句: {}", e.getMessage());
                return List.of(original);
            }
        }

        @Override
        public List<String> expand(String rewrittenQuery) {
            return expand(rewrittenQuery, null);
        }

        /**
         * 解析 LLM 输出的 JSON 字符串数组并做去重、长度过滤；失败时回退为原句。
         */
        public static List<String> parseAndValidate(ObjectMapper objectMapper, String raw, String original,
                                                    int minChars, int maxChars, int maxQueries) {
            List<String> fallback = List.of(original);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            String json = extractJsonArray(raw.trim());
            if (json == null) {
                return fallback;
            }
            try {
                JsonNode root = objectMapper.readTree(json);
                if (!root.isArray()) {
                    return fallback;
                }
                List<String> out = new ArrayList<>();
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                // 原句始终排在首位，保证 BM25 精确匹配不丢失
                if (seen.add(original)) {
                    out.add(original);
                }
                for (JsonNode node : root) {
                    if (!node.isTextual()) {
                        continue;
                    }
                    String query = node.asText().trim();
                    if (query.length() < minChars || query.length() > maxChars) {
                        continue;
                    }
                    if (seen.add(query)) {
                        out.add(query);
                    }
                    if (out.size() >= Math.max(1, maxQueries)) {
                        break;
                    }
                }
                return out.isEmpty() ? fallback : out;
            } catch (Exception e) {
                return fallback;
            }
        }

        /** 容忍 ```json 代码块包裹，截取首个数组片段。 */
        private static String extractJsonArray(String text) {
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return null;
            }
            return text.substring(start, end + 1);
        }
    }
}
