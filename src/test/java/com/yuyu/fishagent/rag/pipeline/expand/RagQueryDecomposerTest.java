package com.yuyu.fishagent.rag.pipeline.expand;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagQueryDecomposerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesValidJsonArrayAndCaps() {
        String raw = "[\"Docker 容器部署最佳实践\", \"Redis 持久化 RDB 与 AOF 详解\", \"x\", \"Spring Boot 容器化步骤\"]";
        List<String> out = RagQueryExpand.LlmQueryDecomposer.parseAndValidate(
                objectMapper, raw, "原始问题", 5, 200, 2);

        assertThat(out).containsExactly("Docker 容器部署最佳实践", "Redis 持久化 RDB 与 AOF 详解");
    }

    @Test
    void stripsCodeFenceAndExtractsArray() {
        String raw = "```json\n[\"今天天气怎么样\"]\n```";
        List<String> out = RagQueryExpand.LlmQueryDecomposer.parseAndValidate(
                objectMapper, raw, "今天天气怎么样", 5, 200, 4);

        assertThat(out).containsExactly("今天天气怎么样");
    }

    @Test
    void fallsBackToOriginalOnInvalidJson() {
        List<String> out = RagQueryExpand.LlmQueryDecomposer.parseAndValidate(
                objectMapper, "not json at all", "原始问题在此", 5, 200, 4);

        assertThat(out).containsExactly("原始问题在此");
    }

    @Test
    void fallsBackToOriginalWhenAllFilteredOut() {
        List<String> out = RagQueryExpand.LlmQueryDecomposer.parseAndValidate(
                objectMapper, "[\"a\", \"b\"]", "原始问题在此", 5, 200, 4);

        assertThat(out).containsExactly("原始问题在此");
    }

    @Test
    void dedupsAndTrims() {
        String raw = "[\" 重复查询内容 \", \"重复查询内容\", \"另一个查询内容\"]";
        List<String> out = RagQueryExpand.LlmQueryDecomposer.parseAndValidate(
                objectMapper, raw, "原始", 2, 200, 4);

        assertThat(out).containsExactly("重复查询内容", "另一个查询内容");
    }

    @Test
    void identityExpanderReturnsSingleTrimmedQuery() {
        RagQueryExpand.IdentityExpander expander = new RagQueryExpand.IdentityExpander();

        assertThat(expander.expand("  你好世界  ")).containsExactly("你好世界");
        assertThat(expander.expand("   ")).isEmpty();
    }
}
