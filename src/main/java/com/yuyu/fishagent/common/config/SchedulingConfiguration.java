package com.yuyu.fishagent.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 Spring 调度：孤儿文档任务补偿等 {@code @Scheduled} Bean 依赖此开关。
 */
@Configuration
@EnableScheduling
public class SchedulingConfiguration {
}
