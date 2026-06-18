package com.yuyu.fishagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.common.dto.ChatMessageDTO;
import com.yuyu.fishagent.memory.compress.MemoryPromptBuilder;
import com.yuyu.fishagent.memory.compress.MemoryResponseParser;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryEvalRunnerTest {

    @Test
    void runnerIsOnlyRegisteredWhenLiveEvalIsEnabled() {
        ConditionalOnProperty condition = SummaryEvalRunner.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("fish.eval.summary");
        assertThat(condition.name()).containsExactly("live-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
    }

    @Test
    void zeroBaselineRetentionProducesInsufficientBaselineDecision() {
        QueueChatModel chatModel = new QueueChatModel(
                json("其他话题", "CLOSED", "", List.of()),
                json("早期信息", "ACTIVE", "", List.of()),
                json("早期信息", "CLOSED", "早期信息", List.of())
        );
        SummaryEvalRunner runner = new SummaryEvalRunner(
                chatModel,
                new MemoryPromptBuilder(new ObjectMapper()),
                new MemoryResponseParser(new ObjectMapper())
        );
        SummaryGoldenSet.Case item = new SummaryGoldenSet.Case(
                "baseline-zero",
                1,
                List.of(
                        new ChatMessageDTO("user", "早期信息", 100),
                        new ChatMessageDTO("assistant", "最近窗口", 200)
                ),
                0,
                List.of("早期信息"),
                List.of(new SummaryGoldenSet.ExpectedTopic("早期信息", "CLOSED")),
                List.of(),
                List.of("早期信息")
        );

        SummaryEvalReport report = runner.run(new SummaryGoldenSet(List.of(item)), 0.9);

        assertThat(report.decisionStatus()).isEqualTo(SummaryEvalReport.DecisionStatus.INSUFFICIENT_BASELINE);
        assertThat(report.reconciliationMeetsEarlyRetentionThreshold()).isFalse();
    }

    @Test
    void runnerIncludesKeyExcerptsWhenScoringModelOutputs() {
        QueueChatModel chatModel = new QueueChatModel(
                json("早期信息", "CLOSED", "", List.of("早期信息")),
                json("早期信息", "ACTIVE", "", List.of()),
                json("早期信息", "CLOSED", "", List.of("早期信息"))
        );
        SummaryEvalRunner runner = new SummaryEvalRunner(
                chatModel,
                new MemoryPromptBuilder(new ObjectMapper()),
                new MemoryResponseParser(new ObjectMapper())
        );
        SummaryGoldenSet.Case item = new SummaryGoldenSet.Case(
                "excerpt-scoring",
                1,
                List.of(
                        new ChatMessageDTO("user", "早期信息", 100),
                        new ChatMessageDTO("assistant", "最近窗口", 200)
                ),
                0,
                List.of("早期信息"),
                List.of(new SummaryGoldenSet.ExpectedTopic("早期信息", "CLOSED")),
                List.of(),
                List.of("早期信息")
        );

        SummaryEvalReport report = runner.run(new SummaryGoldenSet(List.of(item)), 0.9);

        assertThat(report.fullCalibrationAverage().earlyInfoRetention()).isEqualTo(1.0);
        assertThat(report.reconciliationAverage().earlyInfoRetention()).isEqualTo(1.0);
        assertThat(report.decisionStatus()).isEqualTo(SummaryEvalReport.DecisionStatus.PASSED);
    }

    @Test
    void seedSummaryIsBuiltByRepeatedIncrementalUpdatesOverPrefix() {
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
                json("全量基线", "ACTIVE", "baseline", List.of("早期信息")),
                json("seed-1", "ACTIVE", "first incremental seed", List.of()),
                json("seed-2", "ACTIVE", "second incremental seed", List.of()),
                json("全量基线", "ACTIVE", "reconciled", List.of("早期信息"))
        );
        SummaryEvalRunner runner = new SummaryEvalRunner(
                chatModel,
                new MemoryPromptBuilder(new ObjectMapper()),
                new MemoryResponseParser(new ObjectMapper())
        );
        SummaryGoldenSet.Case item = new SummaryGoldenSet.Case(
                "incremental-seed",
                2,
                List.of(
                        new ChatMessageDTO("user", "prefix-1", 100),
                        new ChatMessageDTO("assistant", "prefix-2", 200),
                        new ChatMessageDTO("user", "prefix-3", 300),
                        new ChatMessageDTO("assistant", "window-1", 400),
                        new ChatMessageDTO("user", "window-2", 500)
                ),
                0,
                List.of("全量基线"),
                List.of(new SummaryGoldenSet.ExpectedTopic("全量基线", "ACTIVE")),
                List.of(),
                List.of("早期信息")
        );

        runner.run(new SummaryGoldenSet(List.of(item)), 0.9);

        assertThat(chatModel.prompts).hasSize(4);
        assertThat(chatModel.prompts.get(0).getInstructions().get(1).getText())
                .contains("## 全量对话历史")
                .contains("prefix-1")
                .contains("window-2");
        assertThat(chatModel.prompts.get(1).getInstructions().get(1).getText())
                .contains("## 本轮新增对话")
                .contains("prefix-1")
                .contains("prefix-2")
                .doesNotContain("window-1")
                .doesNotContain("## 全量对话历史");
        assertThat(chatModel.prompts.get(2).getInstructions().get(1).getText())
                .contains("## 当前摘要")
                .contains("seed-1")
                .contains("prefix-3")
                .doesNotContain("window-1")
                .doesNotContain("## 全量对话历史");
        assertThat(chatModel.prompts.get(3).getInstructions().get(1).getText())
                .contains("## 最近窗口对话")
                .contains("seed-2")
                .contains("window-1")
                .contains("window-2")
                .doesNotContain("prefix-1");
    }

    private static String json(String topic, String status, String summary, List<String> excerpts) {
        String excerptJson = excerpts.stream()
                .map(value -> """
                        {"turnIndex":1,"role":"user","content":"%s","reason":"保留"}
                        """.formatted(value))
                .toList()
                .toString();
        return """
                {
                  "structured_summary": {
                    "activeTopics": [{"topic": "%s", "status": "%s", "summary": "%s"}],
                    "keyEntities": {"case": ["%s"]},
                    "pendingIntents": [],
                    "userSignals": {"expertise": "", "communicationStyle": "", "observedPreferences": []}
                  },
                  "key_excerpts": %s,
                  "agent_state": {"phase": "IDLE", "activeTasks": [], "lastDetectedIntent": ""}
                }
                """.formatted(topic, status, summary, topic, excerptJson);
    }

    private static final class QueueChatModel implements ChatModel {

        private final Queue<String> responses;

        private QueueChatModel(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(responses.remove()))));
        }
    }

    private static final class CapturingQueueChatModel implements ChatModel {

        private final Queue<String> responses;
        private final List<Prompt> prompts = new ArrayList<>();

        private CapturingQueueChatModel(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(responses.remove()))));
        }
    }
}
