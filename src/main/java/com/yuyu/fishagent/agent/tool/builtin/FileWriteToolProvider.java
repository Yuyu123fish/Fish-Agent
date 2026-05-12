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
import java.nio.file.StandardOpenOption;
import java.util.function.Function;

/**
 * 沙盒文件写入工具：仅允许写入 {@code fish.agent.sandbox-dir} 之内的文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileWriteToolProvider implements AgentToolProvider {

    private final AgentProperties properties;

    public record Input(String path, String content, Boolean append) {}

    public record Output(String path, int bytesWritten, boolean appended) {}

    @Override
    public String name() {
        return "file_write";
    }

    @Override
    public ToolCallback build() {
        Function<Input, Output> fn = input -> {
            if (input == null || input.path() == null || input.path().isBlank()) {
                return new Output("", 0, false);
            }
            try {
                // 获取沙盒根目录并规范化路径
                Path sandboxRoot = Path.of(properties.getSandboxDir()).toAbsolutePath().normalize();
                Files.createDirectories(sandboxRoot);
                Path target = sandboxRoot.resolve(input.path()).toAbsolutePath().normalize();
                // 防止路径穿越攻击
                if (!target.startsWith(sandboxRoot)) {
                    log.warn("file_write 路径越权: {}", input.path());
                    return new Output(input.path(), 0, false);
                }
                // 确保父目录存在
                Files.createDirectories(target.getParent());
                byte[] data = (input.content() == null ? "" : input.content()).getBytes(StandardCharsets.UTF_8);
                boolean append = Boolean.TRUE.equals(input.append());
                // 根据 append 参数选择追加或覆盖写入
                if (append) {
                    Files.write(target, data,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } else {
                    Files.write(target, data,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                return new Output(input.path(), data.length, append);
            } catch (Exception e) {
                log.warn("file_write 失败: {} - {}", input.path(), e.getMessage());
                return new Output(input.path(), 0, false);
            }
        };
        return FunctionToolCallback.builder(name(), fn)
                .description("向沙盒目录内写入/追加文本文件。append=true 时为追加，否则覆盖写入。")
                .inputType(Input.class)
                .build();
    }
}
