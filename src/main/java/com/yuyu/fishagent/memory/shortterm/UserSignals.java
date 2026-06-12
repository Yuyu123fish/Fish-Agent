package com.yuyu.fishagent.memory.shortterm;

import java.util.List;

/**
 * 从对话中观察到的用户信号，用于让模型维持更稳定的交互风格。
 */
public record UserSignals(
        String expertise,
        String communicationStyle,
        List<String> observedPreferences
) {
}
