package com.yuyu.fishagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 全局配置项。
 * <p>
 * 通过 {@code fish.agent.*} 暴露给 application.yml，集中管控 Agent 行为。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.agent")
public class AgentProperties {

    /**
     * ReAct 循环最大迭代次数，触达后强制中止以防死循环。
     */
    private int maxIterations = 10;

    /**
     * 多轮对话历史持久化目录（相对工作目录或绝对路径）。
     */
    private String historyDir = "data/history";

    /**
     * 文件读写工具沙盒根目录，所有文件 IO 都被限制在该目录之内。
     */
    private String sandboxDir = "data/sandbox";

    /**
     * Agent 系统人设/指令。
     */
    private String instruction = "你是一个名为 Fish 的智能助手，擅长理解用户意图并合理使用工具来解决问题。";
}
