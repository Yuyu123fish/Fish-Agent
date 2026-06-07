package com.yuyu.fishagent.card.extract;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import com.yuyu.fishagent.card.dto.GroupTreeNode;

import java.util.List;

/**
 * 知识卡片提取 prompt 构造器。
 *
 * <p>独立出来是为了后续做 few-shot、已有分组注入、不同模型模板时不污染提取流程。</p>
 */
@Component
public class CardExtractPromptBuilder {

    private static final String SYSTEM = """
            你是一个知识提取专家。你的任务是从用户与助手的对话中提取可复用的知识卡片。
            严禁输出 Markdown、解释、寒暄、代码块或 JSON 之外的任何文字。

            ━━ 提取标准（必须同时满足）━━
            1. 必须是一个完整的、可以独立理解的概念或主题，不能只是一个公式、一条规则或一个事实片段
            2. 必须具有复用价值——用户在未来的学习或工作中会反复用到
            3. 必须有一定深度——不是三言两语就能说清的常识
            4. content 必须包含完整的解释（定义 + 核心要点 + 应用场景或关键细节），不能只有公式或定义

            ━━ 绝对不要提取以下内容 ━━
            - 单个公式或定理（除非附带完整推导或实际应用场景）
            - 编程题的具体解法或代码片段
            - 一句话就能概括的常识（如"变量需要先声明再使用"）
            - 对话中的闲聊、问候、确认等非知识性内容
            - 过于具体的题目或问题场景（如某道算法题的特定解法）
            - 纯粹的事实罗列（如"Java 有 8 种基本类型"）

            ━━ 好的知识卡片示例 ━━
            ✅ "JVM 内存模型"：包含五个区域的完整解释、各自职责、常见问题
            ✅ "Spring IoC 容器原理"：控制反转的概念、DI 实现方式、Bean 生命周期
            ✅ "TCP 三次握手"：完整流程、为什么需要三次、状态变迁

            ━━ 不好的示例（不要提取）━━
            ❌ "走楼梯变体问题"：只是一个 DP 公式，没有独立的知识价值
            ❌ "Java 基本类型"：纯粹的事实罗列，没有深度
            ❌ "for 循环语法"：太基础，没有复用价值

            宁可少提取，也不要提取低质量内容。一次对话通常只有 2-5 个值得提取的知识点。

            ━━ 格式规则 ━━
            1. 每个符合条件的知识点生成一张卡片。
            2. 简单概念用 card_type="concept"，复杂主题用 card_type="topic"。
            3. title 简洁（≤30字），content 使用 Markdown 格式，100-300 字，包含完整解释。
            4. keywords 提取 3-6 个关键标签。
            5. 如果同批卡片存在关系，在 relations 中指出。
            6. relation_type 只能是 related_to、contains、precedes、derived_from。
            7. from_title 和 to_title 必须严格等于同批 cards 中某张卡片的 title。
            8. 根据主题建议 group_name，相同领域尽量使用同一个 group_name。group_name 支持用 "父分组/子分组" 路径格式表示层级关系。如果新卡片属于某个已有分组的子领域，优先在该分组下创建子分组（group_name 用 "父分组/子分组" 格式），而不是创建新的平级分组。

            只输出如下 JSON（cards 数组可以为空，如果没有值得提取的知识点）：
            {
              "cards": [
                {
                  "title": "JVM 内存模型",
                  "content": "JVM 内存分为五个区域：堆、栈、方法区、程序计数器和本地方法栈。\\n\\n**堆（Heap）**是对象实例分配的区域，GC 主要管理此处...\\n\\n**栈（Stack）**存储局部变量和方法调用帧...",
                  "keywords": ["JVM", "内存", "GC"],
                  "card_type": "topic",
                  "group_name": "Java 基础"
                }
              ],
              "relations": [
                {
                  "from_title": "JVM 内存模型",
                  "to_title": "垃圾回收机制",
                  "relation_type": "related_to",
                  "confidence": 0.85
                }
              ]
            }
            """;

    public Prompt buildExtractPrompt(String preparedConversation) {
        return buildExtractPrompt(preparedConversation, List.of(), List.of());
    }

    public Prompt buildExtractPrompt(String preparedConversation, List<String> existingKeywords, List<GroupTreeNode> existingGroupTree) {
        String extra = buildExistingHint(existingKeywords, existingGroupTree);
        return new Prompt(
                new SystemMessage(SYSTEM + extra),
                new UserMessage("待提取对话：\n" + preparedConversation)
        );
    }

    private static String buildExistingHint(List<String> existingKeywords, List<GroupTreeNode> existingGroupTree) {
        StringBuilder sb = new StringBuilder();
        List<String> keywords = existingKeywords == null ? List.of() : existingKeywords.stream()
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .limit(50)
                .toList();
        if (!keywords.isEmpty()) {
            sb.append("\n\n9. 以下是用户已有关键词库，优先复用已有名称，避免同义不同名：\n")
                    .append(String.join("、", keywords));
        }
        if (existingGroupTree != null && !existingGroupTree.isEmpty()) {
            sb.append("\n\n10. 以下是用户已有分组树（缩进表示层级），group_name 优先使用已有分组名：\n");
            renderGroupTree(existingGroupTree, 0, sb);
            sb.append("如果新卡片属于某个已有分组的子领域，优先在该分组下创建子分组（group_name 用 \"父分组/子分组\" 格式），而不是创建新的平级分组。");
        }
        return sb.toString();
    }

    private static void renderGroupTree(List<GroupTreeNode> nodes, int depth, StringBuilder sb) {
        String indent = "  ".repeat(depth);
        for (GroupTreeNode node : nodes) {
            sb.append(indent).append("- ").append(node.name()).append("\n");
            if (node.children() != null && !node.children().isEmpty()) {
                renderGroupTree(node.children(), depth + 1, sb);
            }
        }
    }

    public Prompt buildSummaryPrompt(String conversationPrefix) {
        return new Prompt(
                new SystemMessage("""
                        你是对话摘要器。请把以下较长对话压缩成 300 字以内摘要，保留主题、结论、关键概念和上下文。
                        只输出摘要正文，不要输出 JSON、标题或解释。
                        """),
                new UserMessage(conversationPrefix)
        );
    }
}
