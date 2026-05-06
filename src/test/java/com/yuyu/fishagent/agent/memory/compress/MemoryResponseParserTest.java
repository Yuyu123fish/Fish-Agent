package com.yuyu.fishagent.agent.memory.compress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.dto.MemoryCompressionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryResponseParserTest {

    private final MemoryResponseParser parser = new MemoryResponseParser(new ObjectMapper());

    @Test
    void parseValidJson() {
        MemoryCompressionResult result = parser.parse("""
                {
                  "short_term_summary": "用户正在实现记忆压缩。",
                  "long_term_facts": ["用户偏好中文回答", "项目使用 Spring Boot"]
                }
                """);

        assertThat(result.getShortTermSummary()).isEqualTo("用户正在实现记忆压缩。");
        assertThat(result.getLongTermFacts()).containsExactly("用户偏好中文回答", "项目使用 Spring Boot");
    }

    @Test
    void parseEmptyLongTermFacts() {
        MemoryCompressionResult result = parser.parse("""
                ```json
                {
                  "short_term_summary": "继续当前问题即可。",
                  "long_term_facts": []
                }
                ```
                """);

        assertThat(result.getShortTermSummary()).isEqualTo("继续当前问题即可。");
        assertThat(result.getLongTermFacts()).isEmpty();
    }

    @Test
    void rejectInvalidJson() {
        assertThatThrownBy(() -> parser.parse("not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid JSON");
    }

    @Test
    void rejectNonStringFacts() {
        assertThatThrownBy(() -> parser.parse("""
                {
                  "short_term_summary": "summary",
                  "long_term_facts": [1]
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strings");
    }
}
