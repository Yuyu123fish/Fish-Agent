package com.yuyu.fishagent.eval;

import com.yuyu.fishagent.memory.shortterm.StructuredSummary;
import com.yuyu.fishagent.memory.compress.MemoryResponseParser;
import com.yuyu.fishagent.memory.shortterm.KeyExcerpt;
import com.yuyu.fishagent.memory.shortterm.TopicSegment;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 摘要 eval 的规则指标计算器。
 *
 * <p>这里不接 LLM，只对结构化摘要和人工 golden 期望做确定性匹配，保证指标可回归、可进 CI。</p>
 */
public final class SummaryEvalMetrics {

    private SummaryEvalMetrics() {
    }

    public record Result(double keyEntityRecall,
                         double topicStatusAccuracy,
                         double earlyInfoRetention,
                         double structValid) {
    }

    /**
     * 按 golden case 计算摘要质量指标。
     *
     * @param summary 模型输出解析后的结构化摘要；为空视为结构无效
     * @param expected golden 期望
     * @return 四项规则指标，取值范围均为 0..1
     */
    public static Result eval(StructuredSummary summary, SummaryGoldenSet.Case expected) {
        return eval(summary, List.of(), expected);
    }

    /**
     * 按完整结构化压缩结果计算指标，包含 summary 平级的 keyExcerpts。
     */
    public static Result eval(MemoryResponseParser.StructuredCompressionResult result, SummaryGoldenSet.Case expected) {
        if (result == null) {
            return new Result(0.0, 0.0, 0.0, 0.0);
        }
        return eval(result.summary(), result.keyExcerpts(), expected);
    }

    private static Result eval(StructuredSummary summary, List<KeyExcerpt> keyExcerpts, SummaryGoldenSet.Case expected) {
        if (summary == null || expected == null) {
            return new Result(0.0, 0.0, 0.0, 0.0);
        }
        return new Result(
                keyEntityRecall(summary, expected.expectedKeyEntities()),
                topicStatusAccuracy(summary, expected.expectedActiveTopics()),
                earlyInfoRetention(summary, keyExcerpts, expected.mustNotLose()),
                1.0
        );
    }

    private static double keyEntityRecall(StructuredSummary summary, List<String> expectedEntities) {
        List<String> expected = cleanList(expectedEntities);
        if (expected.isEmpty()) {
            return 1.0;
        }
        if (summary.keyEntities() == null) {
            return 0.0;
        }
        var actualEntities = summary.keyEntities().values().stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(SummaryEvalMetrics::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
        int matched = 0;
        for (String item : expected) {
            if (actualEntities.contains(normalize(item))) {
                matched++;
            }
        }
        return matched / (double) expected.size();
    }

    private static double topicStatusAccuracy(StructuredSummary summary, List<SummaryGoldenSet.ExpectedTopic> expectedTopics) {
        List<SummaryGoldenSet.ExpectedTopic> expected = expectedTopics == null ? List.of() : expectedTopics.stream()
                .filter(Objects::nonNull)
                .filter(topic -> !isBlank(topic.topic()))
                .toList();
        if (expected.isEmpty()) {
            return 1.0;
        }
        List<TopicSegment> topics = summary.activeTopics() == null ? List.of() : summary.activeTopics();
        int matched = 0;
        for (SummaryGoldenSet.ExpectedTopic expectedTopic : expected) {
            String expectedName = normalize(expectedTopic.topic());
            String expectedStatus = normalizeStatus(expectedTopic.status());
            boolean hit = topics.stream()
                    .filter(Objects::nonNull)
                    .anyMatch(topic -> normalize(topic.topic()).equals(expectedName)
                            && normalizeStatus(topic.status()).equals(expectedStatus));
            if (hit) {
                matched++;
            }
        }
        return matched / (double) expected.size();
    }

    private static double earlyInfoRetention(StructuredSummary summary, List<KeyExcerpt> keyExcerpts, List<String> mustNotLose) {
        List<String> expected = cleanList(mustNotLose);
        if (expected.isEmpty()) {
            return 1.0;
        }
        return recall(expected, normalize(summaryText(summary, keyExcerpts)));
    }

    private static double recall(List<String> expected, String actualText) {
        int matched = 0;
        for (String item : expected) {
            if (actualText.contains(normalize(item))) {
                matched++;
            }
        }
        return matched / (double) expected.size();
    }

    /**
     * 汇总摘要全文，用于检测早期关键信息是否被任何结构化字段保留下来。
     */
    private static String summaryText(StructuredSummary summary, List<KeyExcerpt> keyExcerpts) {
        StringBuilder text = new StringBuilder();
        if (summary.activeTopics() != null) {
            for (TopicSegment topic : summary.activeTopics()) {
                if (topic != null) {
                    text.append(topic.topic()).append('\n')
                            .append(topic.status()).append('\n')
                            .append(topic.summary()).append('\n');
                }
            }
        }
        if (summary.keyEntities() != null) {
            for (Map.Entry<String, List<String>> entry : summary.keyEntities().entrySet()) {
                text.append(entry.getKey()).append('\n');
                if (entry.getValue() != null) {
                    entry.getValue().forEach(value -> text.append(value).append('\n'));
                }
            }
        }
        if (summary.pendingIntents() != null) {
            summary.pendingIntents().forEach(intent -> text.append(intent).append('\n'));
        }
        if (summary.userSignals() != null) {
            text.append(summary.userSignals().expertise()).append('\n')
                    .append(summary.userSignals().communicationStyle()).append('\n');
            if (summary.userSignals().observedPreferences() != null) {
                summary.userSignals().observedPreferences().forEach(preference -> text.append(preference).append('\n'));
            }
        }
        if (keyExcerpts != null) {
            for (KeyExcerpt excerpt : keyExcerpts) {
                if (excerpt != null) {
                    text.append(excerpt.content()).append('\n');
                }
            }
        }
        return text.toString();
    }

    private static List<String> cleanList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> !isBlank(value))
                .toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeStatus(String value) {
        return normalize(value).toUpperCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
