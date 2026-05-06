package com.yuyu.fishagent.agent;

/**
 * Agent 运行时状态，用于配合 {@code maxIterations} 双重防御死循环并暴露可观测信号。
 */
public enum AgentStatus {

    /** 空闲态，可接受新一轮请求。 */
    IDLE,

    /** 正在思考/调用工具的运行态。 */
    RUNNING,

    /** 正常完成。 */
    FINISHED,

    /** 执行过程中抛出异常。 */
    ERROR,

    /** 触达最大迭代次数被强制中止。 */
    MAX_ITER_REACHED
}
