package com.yuyu.fishagent.agent.tool.builtin;

import com.yuyu.fishagent.agent.tool.AgentToolProvider;
import com.yuyu.fishagent.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * 沙盒文件读取工具：仅允许读取 {@code fish.agent.sandbox-dir} 之内的文件，
 * 杜绝任意路径穿越。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileReadToolProvider implements AgentToolProvider {

    private final AgentProperties properties;

    public record Input(String path) {}

    public record Output(String path, String content) {}

    @Override
    public String name() {
        return "file_read";
    }

    @Override
    public ToolCallback build() {
        Function<Input, Output> fn = input -> {
            if (input == null || input.path() == null || input.path().isBlank()) {
                return new Output("", "ERROR: path is required");
            }
            try {
                // 获取沙盒根目录并规范化路径
                Path sandboxRoot = Path.of(properties.getSandboxDir()).toAbsolutePath().normalize();
                Files.createDirectories(sandboxRoot);
                Path target = sandboxRoot.resolve(input.path()).toAbsolutePath().normalize();
                // 防止路径穿越攻击
                if (!target.startsWith(sandboxRoot)) {
                    return new Output(input.path(), "ERROR: path escapes sandbox");
                }
                if (!Files.exists(target) || !Files.isRegularFile(target)) {
                    return new Output(input.path(), "ERROR: file not found");
                }
                byte[] all = Files.readAllBytes(target);
                return new Output(input.path(), new String(all, StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.warn("file_read 失败: {} - {}", input.path(), e.getMessage());
                return new Output(input.path(), "ERROR: " + e.getMessage());
            }
        };
        return FunctionToolCallback.builder(name(), fn)
                .description("读取沙盒目录下的文本文件内容，长结果由工具注册中心统一治理。path 为相对沙盒根目录的相对路径。")
                .inputType(Input.class)
                .build();
    }
}
