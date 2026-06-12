package com.yuyu.fishagent.memory.compress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.memory.dto.MemoryCompressionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void parseStructuredCompressionResult() {
        MemoryResponseParser.StructuredCompressionResult result = parser.parseStructured("""
                {
                  "structured_summary": {
                    "activeTopics": [
                      {"topic": "短期记忆", "status": "ACTIVE", "summary": "正在做增量压缩"}
                    ],
                    "keyEntities": {"项目": ["Fish-Agent"]},
                    "pendingIntents": ["继续执行阶段二"],
                    "userSignals": {
                      "expertise": "后端开发",
                      "communicationStyle": "直接",
                      "observedPreferences": ["中文回答"]
                    }
                  },
                  "key_excerpts": [
                    {"turnIndex": 3, "role": "user", "content": "不要中断", "reason": "执行约束"}
                  ],
                  "agent_state": {"phase": "EXECUTING", "lastDetectedIntent": "执行方案"}
                }
                """);

        assertThat(result.summary().activeTopics())
                .extracting("topic", "status", "summary")
                .containsExactly(org.assertj.core.api.Assertions.tuple("短期记忆", "ACTIVE", "正在做增量压缩"));
        assertThat(result.summary().keyEntities()).containsEntry("项目", List.of("Fish-Agent"));
        assertThat(result.keyExcerpts()).hasSize(1);
        assertThat(result.agentStateNode().path("phase").asText()).isEqualTo("EXECUTING");
    }
}
