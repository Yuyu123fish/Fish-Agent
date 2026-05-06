package com.yuyu.fishagent.agent.tool.builtin;

import com.yuyu.fishagent.agent.tool.AgentToolProvider;
import com.yuyu.fishagent.config.AgentProperties;
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

    private static final long MAX_BYTES = 256 * 1024L;

    private final AgentProperties properties;

    public record Input(String path) {}

    public record Output(String path, String content, boolean truncated) {}

    @Override
    public String name() {
        return "file_read";
    }

    @Override
    public ToolCallback build() {
        Function<Input, Output> fn = input -> {
            if (input == null || input.path() == null || input.path().isBlank()) {
                return new Output("", "ERROR: path is required", false);
            }
            try {
                // 获取沙盒根目录并规范化路径
                Path sandboxRoot = Path.of(properties.getSandboxDir()).toAbsolutePath().normalize();
                Files.createDirectories(sandboxRoot);
                Path target = sandboxRoot.resolve(input.path()).toAbsolutePath().normalize();
                // 防止路径穿越攻击
                if (!target.startsWith(sandboxRoot)) {
                    return new Output(input.path(), "ERROR: path escapes sandbox", false);
                }
                if (!Files.exists(target) || !Files.isRegularFile(target)) {
                    return new Output(input.path(), "ERROR: file not found", false);
                }
                // 限制最大读取字节数，防止内存溢出
                byte[] all = Files.readAllBytes(target);
                boolean truncated = all.length > MAX_BYTES;
                byte[] limited = truncated ? java.util.Arrays.copyOf(all, (int) MAX_BYTES) : all;
                return new Output(input.path(), new String(limited, StandardCharsets.UTF_8), truncated);
            } catch (Exception e) {
                log.warn("file_read 失败: {} - {}", input.path(), e.getMessage());
                return new Output(input.path(), "ERROR: " + e.getMessage(), false);
            }
        };
        return FunctionToolCallback.builder(name(), fn)
                .description("读取沙盒目录下的文本文件内容（最大 256KB，超出截断）。path 为相对沙盒根目录的相对路径。")
                .inputType(Input.class)
                .build();
    }
}
