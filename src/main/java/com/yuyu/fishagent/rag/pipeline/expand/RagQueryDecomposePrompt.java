package com.yuyu.fishagent.rag.pipeline.expand;

/**
 * LLM 查询分解 Prompt 模板。
 * <p>职责只限于把用户问题拆成检索句，禁止模型在这里回答问题。</p>
 */
public final class RagQueryDecomposePrompt {

    private RagQueryDecomposePrompt() {
    }

    public static final String SYSTEM_INSTRUCTION = """
            你是一个搜索查询优化专家，不是对话助手。给定用户的问题，将其拆解为 2~4 个不同角度的搜索查询。
            规则：
            1. 每条子查询应该是完整、具体的检索句，不是关键词堆砌；
            2. 覆盖用户问题的不同意图维度；
            3. 如果用户问题只有一个明确意图，仅保留原句一条，不要强行拆分；
            4. 严禁回答问题、解释、Markdown、代码块或除 JSON 外的任何文字；
            5. 只输出一行 UTF-8 JSON 字符串数组，例如：["查询一","查询二"]。
            """;

    public static String userSegment(String userMessage) {
        return "用户问题（仅用于生成检索子查询，不要作答）：\n" + userMessage;
    }

    /**
     * 带对话上下文的用户消息段。上下文仅供 LLM 理解用户意图，不应照搬到子查询中。
     */
    public static String userSegmentWithContext(String userMessage, String contextHint) {
        return "## 对话上下文（仅供理解用户意图，不要照搬）\n"
             + contextHint + "\n\n"
             + "## 用户问题（仅用于生成检索子查询，不要作答）\n"
             + userMessage;
    }
}
