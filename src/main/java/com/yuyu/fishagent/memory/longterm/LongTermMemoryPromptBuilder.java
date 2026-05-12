package com.yuyu.fishagent.memory.longterm;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 长期记忆主动录入 Prompt 构建器。
 * <p>只判断“当前用户输入”里是否存在稳定事实，避免把短期摘要或普通闲聊写入 ES。</p>
 */
@Component
public class LongTermMemoryPromptBuilder {

    private static final String SYSTEM_PROMPT = """
            你是 Fish-Agent 的长期记忆筛选器。你的任务是判断当前用户输入是否包含值得长期保存的稳定事实。

            只允许提取以下类型：
            1. 用户身份信息：姓名、昵称、职业、项目背景、长期角色。
            2. 用户明确偏好：喜欢什么、不喜欢什么、长期习惯、稳定技术偏好。
            3. 长期目标或约束：正在长期开发的项目、固定规划、持续性要求。

            必须过滤：
            1. 普通寒暄、一次性问题、临时任务、上下文摘要。
            2. 疑问句中的未确认事实，例如“我是谁？”不要保存。
            3. 助手推测、情绪抱怨、工具执行过程。
            4. 对 Fish-Agent、本助手、对话系统的功能介绍、架构或技术栈说明（如「具备 RAG、ReAct、工具调用、记忆」等），
               即使用户话里提到了产品名，也不要当作用户长期事实写入；仅当用户以第一人称描述与自己长期相关的角色/职责时
               才可提取（例如「我是 Fish-Agent 项目的维护者」→ 可存为职业/项目事实）。

            输出必须是严格 JSON，不要 markdown，不要解释：
            {
              "long_term_facts": ["事实 1"]
            }
            如果没有值得保存的事实，必须返回：
            {
              "long_term_facts": []
            }
            """;

    /**
     * 构建长期事实提取 Prompt。
     *
     * @param userInput 当前轮用户输入
     * @return 用于判断是否主动录入长期记忆的 Prompt
     */
    public Prompt build(String userInput) {
        return new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("当前用户输入：\n" + (userInput == null ? "" : userInput))
        ));
    }
}
