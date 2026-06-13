package com.yuyu.fishagent.rag.pipeline.recall;

/**
 * RAG 来源权威度与展示标签的统一规则。
 *
 * <p>当前系统只有 PRIVATE / PUBLIC 两条文档入库路径：PRIVATE 表示用户私有知识，默认 0.7；
 * PUBLIC 表示组织/官方知识库，默认 1.0。若后续接入低权威公开语料，可通过入库 authority 调低，标签会自然显示为“公开”。</p>
 */
public final class SourceAuthority {

    public static final double PRIVATE_KNOWLEDGE_AUTHORITY = 0.7;
    public static final double OFFICIAL_AUTHORITY_THRESHOLD = 0.95;

    private SourceAuthority() {
    }

    public static String labelForKnowledge(Double authority, boolean publicScope) {
        if (authority != null && authority >= OFFICIAL_AUTHORITY_THRESHOLD) {
            return "官方";
        }
        return publicScope ? "公开" : "用户";
    }

    public static double defaultAuthority(String label) {
        return switch (label) {
            case "官方" -> 1.0;
            case "用户", "记忆" -> PRIVATE_KNOWLEDGE_AUTHORITY;
            default -> 0.5;
        };
    }
}
