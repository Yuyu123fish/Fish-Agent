package com.yuyu.fishagent.agent.memory.longterm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermMemoryFactSanitizerTest {

    @Test
    void dropsProductCapabilityBlurb() {
        assertThat(LongTermMemoryFactSanitizer.shouldDropAgentProductBlurb(
                "Fish-Agent 是一个具备思考推理、工具调用、记忆、RAG 等功能的 ReAct 智能体")).isTrue();
    }

    @Test
    void keepsUserAnchoredFishAgentFact() {
        assertThat(LongTermMemoryFactSanitizer.shouldDropAgentProductBlurb(
                "用户是 Fish-Agent 的开发者")).isFalse();
    }

    @Test
    void keepsUnrelatedUserFact() {
        assertThat(LongTermMemoryFactSanitizer.shouldDropAgentProductBlurb("用户喜欢喝可乐")).isFalse();
    }

    @Test
    void forIndexingFiltersList() {
        List<String> in = List.of("用户名叫鱼鱼", "Fish-Agent 是一个具备 RAG 的系统", "用户喜欢喝可乐");
        assertThat(LongTermMemoryFactSanitizer.forIndexing(in))
                .containsExactly("用户名叫鱼鱼", "用户喜欢喝可乐");
    }
}
