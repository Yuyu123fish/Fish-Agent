package com.yuyu.fishagent.eval;

import com.yuyu.fishagent.common.dto.ChatMessageDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * 摘要 eval 的 golden set 数据结构，与 RAG golden set 解耦。
 *
 * <p>摘要输出本身是结构化数据，因此期望值也按实体、话题状态、待办和早期保留信息拆分，
 * 便于稳定计算规则指标并进入 CI。</p>
 */
public record SummaryGoldenSet(List<Case> cases) {

    public SummaryGoldenSet {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record Case(
            String id,
            int windowSize,
            List<ChatMessageDTO> session,
            int syntheticNoiseTurns,
            List<String> expectedKeyEntities,
            List<ExpectedTopic> expectedActiveTopics,
            List<String> expectedPendingIntents,
            List<String> mustNotLose
    ) {
        public Case {
            session = session == null ? List.of() : List.copyOf(session);
            if (syntheticNoiseTurns > 0) {
                session = expandSyntheticSession(session, syntheticNoiseTurns);
            }
            expectedKeyEntities = expectedKeyEntities == null ? List.of() : List.copyOf(expectedKeyEntities);
            expectedActiveTopics = expectedActiveTopics == null ? List.of() : List.copyOf(expectedActiveTopics);
            expectedPendingIntents = expectedPendingIntents == null ? List.of() : List.copyOf(expectedPendingIntents);
            mustNotLose = mustNotLose == null ? List.of() : List.copyOf(mustNotLose);
        }

        public Case(String id,
                    int windowSize,
                    List<ChatMessageDTO> session,
                    List<String> expectedKeyEntities,
                    List<ExpectedTopic> expectedActiveTopics,
                    List<String> expectedPendingIntents,
                    List<String> mustNotLose) {
            this(id, windowSize, session, 0, expectedKeyEntities, expectedActiveTopics, expectedPendingIntents, mustNotLose);
        }
    }

    public record ExpectedTopic(String topic, String status) {
    }

    /**
     * 测试资源用少量人工标注消息加合成噪声扩成长会话，保留“早期事实”和“最近窗口”的相对位置。
     */
    private static List<ChatMessageDTO> expandSyntheticSession(List<ChatMessageDTO> seed, int syntheticNoiseTurns) {
        if (seed.isEmpty()) {
            return List.of();
        }
        int tailSize = Math.min(4, seed.size());
        int insertAt = Math.max(1, seed.size() - tailSize);
        List<ChatMessageDTO> expanded = new ArrayList<>(seed.size() + syntheticNoiseTurns);
        expanded.addAll(seed.subList(0, insertAt));
        long baseTime = seed.get(insertAt - 1) == null ? 0 : seed.get(insertAt - 1).getCreatedAt();
        for (int i = 1; i <= syntheticNoiseTurns; i++) {
            expanded.add(new ChatMessageDTO(
                    i % 2 == 0 ? "assistant" : "user",
                    "长会话噪声消息 " + i,
                    baseTime + i
            ));
        }
        expanded.addAll(seed.subList(insertAt, seed.size()));
        return List.copyOf(expanded);
    }
}
