package com.yuyu.fishagent.rag.pipeline.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.regex.Pattern;

/**
 * 查询重写（RAG 第一类）：只负责「用户输入 → 检索用短句」，与扩展、ES 召回无编译期耦合。
 */
public final class RagQueryRewrite {

    private RagQueryRewrite() {
    }

    /** 重写时可用的最小上下文（会话等），不参与模型「作答」逻辑。 */
    public record RewriteContext(String sessionId) {
    }

    /**
     * 查询重写 SPI：输出供后续分词与 ES 使用的字符串。
     * <p>实现必须遵守：只产出检索向文本，不得回答用户问题（见 {@link ChatModelRewriter}）。</p>
     */
    @FunctionalInterface
    public interface QueryRewriter {
        String rewrite(String rawUserInput, RewriteContext ctx);
    }

    /**
     * 恒等重写：trim + 连续空白压成单空格，无大模型调用，语义与原文一致。
     */
    public static final class IdentityRewriter implements QueryRewriter {

        private static final Pattern WHITESPACE = Pattern.compile("\\s+");

        @Override
        public String rewrite(String rawUserInput, RewriteContext ctx) {
            if (rawUserInput == null) {
                return "";
            }
            String t = rawUserInput.trim();
            if (t.isEmpty()) {
                return "";
            }
            // 将各类空白统一为空格，避免 ES match 被奇怪换行干扰
            return WHITESPACE.matcher(t).replaceAll(" ").trim();
        }
    }

    /**
     * 基于 {@link ChatModel} 的检索向重写：仅接受根对象单键 {@code rewritten_query} 的 JSON。
     */
    @Slf4j
    public static final class ChatModelRewriter implements QueryRewriter {

        private static final String STRICT_JSON_INSTRUCTION = """
                你是「检索查询重写器」，不是对话助手。你的唯一任务：把用户输入改写成更适合关键词/向量检索的短查询句。
                严禁：回答问题、解释、推理过程、Markdown、代码、工具调用、多轮对话、除 JSON 外的任何文字。
                只输出一行 UTF-8 JSON 对象，且对象内必须有且仅有键 rewritten_query，值为字符串。
                示例：{"rewritten_query":"用户偏好 拿铁 咖啡"}
                """;

        private final ChatModel chatModel;
        private final RagProperties ragProperties;
        private final ObjectMapper objectMapper;
        private final IdentityRewriter fallback = new IdentityRewriter();

        public ChatModelRewriter(ChatModel chatModel, RagProperties ragProperties, ObjectMapper objectMapper) {
            this.chatModel = chatModel;
            this.ragProperties = ragProperties;
            this.objectMapper = objectMapper;
        }

        @Override
        public String rewrite(String rawUserInput, RewriteContext ctx) {
            String normalized = fallback.rewrite(rawUserInput, ctx);
            if (normalized.isEmpty()) {
                return "";
            }
            try {
                // 单轮 Prompt：系统定角色与输出格式，用户段只贴原文，降低模型「抢答」概率
                Prompt prompt = new Prompt(
                        new SystemMessage(STRICT_JSON_INSTRUCTION),
                        new UserMessage("用户原始输入（仅用于改写，不要作答）：\n" + normalized)
                );
                String raw = chatModel.call(prompt).getResult().getOutput().getText();
                if (raw == null || raw.isBlank()) {
                    log.debug("[RagQueryRewrite.ChatModelRewriter] 模型输出为空，回退规范化原文");
                    return normalized;
                }
                String extracted = tryParseRewrittenJson(raw.trim());
                if (extracted == null) {
                    log.debug("[RagQueryRewrite.ChatModelRewriter] JSON 解析失败，回退规范化原文");
                    return normalized;
                }
                String collapsed = fallback.rewrite(extracted, ctx);
                if (collapsed.isEmpty()) {
                    return normalized;
                }
                if (collapsed.length() > ragProperties.getRewriteMaxChars()) {
                    log.debug("[RagQueryRewrite.ChatModelRewriter] 改写超长 len={}，回退规范化原文", collapsed.length());
                    return normalized;
                }
                return collapsed;
            } catch (Exception e) {
                log.warn("[RagQueryRewrite.ChatModelRewriter] 调用失败，回退规范化原文: {}", e.getMessage());
                return normalized;
            }
        }

        /** 仅接受形如 {"rewritten_query":"..."} 且仅此一键的对象 */
        private String tryParseRewrittenJson(String text) {
            try {
                JsonNode root = objectMapper.readTree(text);
                if (!root.isObject() || root.size() != 1 || !root.has("rewritten_query")) {
                    return null;
                }
                JsonNode v = root.get("rewritten_query");
                if (!v.isTextual()) {
                    return null;
                }
                return v.asText();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
